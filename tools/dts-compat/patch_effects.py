#!/usr/bin/env python3
"""Add DTS to an offline audio_effects copy without changing other effects.

Usage: python patch_effects.py INPUT OUTPUT [--format xml|conf]
Legacy conf defaults to the 64-bit vendor library path; use --library-path for
a different target. This tool does not mount files or change a connected device.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tempfile
from xml.dom import Node, minidom
from xml.parsers.expat import ExpatError


LIBRARY_NAME = "dtsaudio"
LIBRARY_FILE = "libdtsaudio.so"
EFFECT_NAME = "dts_audio"
EFFECT_UUID = "146edfc0-7ed2-11e4-80eb-0002a5d5c51b"
DEFAULT_CONF_LIBRARY = "/vendor/lib64/soundfx/libdtsaudio.so"


class PatchError(ValueError):
    """The input cannot be merged without an ambiguous or conflicting change."""


def _same_uuid(value: str) -> bool:
    return value.casefold() == EFFECT_UUID


def _is_dts_path(value: str) -> bool:
    return PurePosixPath(value).name == LIBRARY_FILE


def _elements(parent: Node, name: str | None = None) -> list[Node]:
    return [
        child for child in parent.childNodes
        if child.nodeType == Node.ELEMENT_NODE
        and (name is None or (
            child.localName == name and child.namespaceURI == parent.namespaceURI
        ))
    ]


def _one_xml(parent: Node, name: str) -> Node | None:
    found = _elements(parent, name)
    if len(found) > 1:
        raise PatchError(f"Multiple {name} elements are ambiguous")
    return found[0] if found else None


def _new_xml(document: minidom.Document, parent: Node, tag_name: str, **attrs: str) -> Node:
    qualified_name = f"{parent.prefix}:{tag_name}" if parent.prefix else tag_name
    element = document.createElementNS(parent.namespaceURI, qualified_name)
    for key, value in attrs.items():
        element.setAttribute(key, value)
    return element


def _append_xml(parent: Node, child: Node, depth: int) -> None:
    document = parent.ownerDocument
    parent.appendChild(document.createTextNode("\n" + "  " * depth))
    parent.appendChild(child)
    parent.appendChild(document.createTextNode("\n" + "  " * (depth - 1)))


def patch_xml(source: str) -> str:
    """Return a validated XML merge; retain all existing nodes and attributes."""
    # Audio effect configuration needs no DTD. Reject it before parsing entities.
    if re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", source, re.IGNORECASE):
        raise PatchError("DTD/entity declarations are not supported")
    try:
        document = minidom.parseString(source)
    except (ExpatError, ValueError) as error:
        raise PatchError(f"Malformed XML: {error}") from error
    root = document.documentElement
    if root.localName != "audio_effects_conf":
        raise PatchError("Expected audio_effects_conf as the XML root")

    libraries = _one_xml(root, "libraries")
    effects = _one_xml(root, "effects")
    postprocess = _one_xml(root, "postprocess")
    matching_library = None
    if libraries is not None:
        for entry in _elements(libraries):
            name, path = entry.getAttribute("name"), entry.getAttribute("path")
            if name == LIBRARY_NAME:
                if matching_library is not None:
                    raise PatchError("Duplicate dtsaudio library")
                if (entry.localName != "library" or entry.namespaceURI != libraries.namespaceURI
                        or path != LIBRARY_FILE):
                    raise PatchError("The dtsaudio library name is already used differently")
                matching_library = entry
            elif _is_dts_path(path):
                raise PatchError("libdtsaudio.so is already registered under another name")

    matching_effect = None
    if effects is not None:
        for entry in effects.getElementsByTagName("*"):
            name, uuid = entry.getAttribute("name"), entry.getAttribute("uuid")
            if name == EFFECT_NAME or _same_uuid(uuid):
                if (entry.parentNode is not effects or entry.localName != "effect"
                        or entry.namespaceURI != effects.namespaceURI
                        or name != EFFECT_NAME or not _same_uuid(uuid)
                        or entry.getAttribute("library") != LIBRARY_NAME):
                    raise PatchError("The DTS effect name or UUID is already used differently")
                if matching_effect is not None:
                    raise PatchError("Duplicate DTS effect UUID/name")
                matching_effect = entry

    music = None
    matching_apply = None
    if postprocess is not None:
        streams = [entry for entry in _elements(postprocess, "stream")
                   if entry.getAttribute("type") == "music"]
        if len(streams) > 1:
            raise PatchError("Multiple music postprocess streams are ambiguous")
        music = streams[0] if streams else None
        if music is not None:
            applies = [entry for entry in _elements(music, "apply")
                       if entry.getAttribute("effect") == EFFECT_NAME]
            if len(applies) > 1:
                raise PatchError("Duplicate DTS apply in the music stream")
            matching_apply = applies[0] if applies else None

    if matching_library is not None and matching_effect is not None and matching_apply is not None:
        return source

    if libraries is None:
        libraries = _new_xml(document, root, "libraries")
        _append_xml(root, libraries, 1)
    if matching_library is None:
        _append_xml(libraries, _new_xml(document, libraries, "library",
                                      name=LIBRARY_NAME, path=LIBRARY_FILE), 2)
    if effects is None:
        effects = _new_xml(document, root, "effects")
        _append_xml(root, effects, 1)
    if matching_effect is None:
        _append_xml(effects, _new_xml(document, effects, "effect", name=EFFECT_NAME,
                                    library=LIBRARY_NAME, uuid=EFFECT_UUID), 2)
    if postprocess is None:
        postprocess = _new_xml(document, root, "postprocess")
        _append_xml(root, postprocess, 1)
    if music is None:
        music = _new_xml(document, postprocess, "stream", type="music")
        _append_xml(postprocess, music, 2)
    if matching_apply is None:
        _append_xml(music, _new_xml(document, music, "apply", effect=EFFECT_NAME), 3)
    return document.toxml(encoding="utf-8").decode("utf-8")


@dataclass(frozen=True)
class _Token:
    value: str
    start: int
    end: int
    kind: str = "word"


@dataclass
class _Field:
    name: str
    value: str


@dataclass
class _Block:
    name: str
    close: int
    depth: int
    children: list[_Block | _Field] = field(default_factory=list)


def _tokens(source: str) -> list[_Token]:
    result = []
    index, size = 0, len(source)
    while index < size:
        char = source[index]
        if char.isspace() or char == "\ufeff":
            index += 1
        elif char == "#" or source.startswith("//", index):
            end = source.find("\n", index)
            index = size if end < 0 else end + 1
        elif source.startswith("/*", index):
            end = source.find("*/", index + 2)
            if end < 0:
                raise PatchError("Unterminated conf block comment")
            index = end + 2
        elif char in "{}":
            result.append(_Token(char, index, index + 1, char))
            index += 1
        elif char in "\"'":
            start, quote, value = index, char, []
            index += 1
            while index < size and source[index] != quote:
                if source[index] == "\\":
                    index += 1
                    if index >= size:
                        raise PatchError("Unterminated conf escape")
                value.append(source[index])
                index += 1
            if index >= size:
                raise PatchError("Unterminated conf quoted value")
            index += 1
            result.append(_Token("".join(value), start, index))
        else:
            start = index
            while (index < size and not source[index].isspace()
                   and source[index] not in "{}#\"'"
                   and not source.startswith(("//", "/*"), index)):
                index += 1
            if index == start:
                raise PatchError(f"Unsupported conf token at character {index}")
            result.append(_Token(source[start:index], start, index))
    return result


def _parse_conf(source: str) -> _Block:
    tokens = _tokens(source)
    root = _Block("<root>", len(source), -1)
    stack = [root]
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token.kind == "}":
            if len(stack) == 1:
                raise PatchError("Unexpected conf closing brace")
            stack.pop().close = token.start
            index += 1
            continue
        if token.kind != "word" or index + 1 >= len(tokens):
            raise PatchError("Expected a conf name followed by a block or value")
        following = tokens[index + 1]
        if following.kind == "{":
            block = _Block(token.value, -1, len(stack) - 1)
            stack[-1].children.append(block)
            stack.append(block)
        elif following.kind == "word":
            stack[-1].children.append(_Field(token.value, following.value))
        else:
            raise PatchError(f"Missing value for conf key {token.value}")
        index += 2
    if len(stack) != 1:
        raise PatchError("Unterminated conf block")
    if not tokens:
        raise PatchError("Empty audio effects conf")
    return root


def _one_block(parent: _Block | None, name: str) -> _Block | None:
    if parent is None:
        return None
    found = [entry for entry in parent.children if entry.name == name]
    if len(found) > 1 or (found and not isinstance(found[0], _Block)):
        raise PatchError(f"Conf {name} is duplicated or is not a block")
    return found[0] if found else None


def _field_value(block: _Block, name: str) -> str:
    fields = [entry for entry in block.children if entry.name == name]
    if len(fields) != 1 or not isinstance(fields[0], _Field):
        raise PatchError(f"Expected exactly one {name} value inside {block.name}")
    return fields[0].value


def _walk_blocks(block: _Block):
    for child in block.children:
        if isinstance(child, _Block):
            yield child
            yield from _walk_blocks(child)


def _conf_library_path(path: str) -> str:
    parsed = PurePosixPath(path)
    if (not parsed.is_absolute() or not _is_dts_path(path) or ".." in parsed.parts
            or any(ord(char) < 32 or ord(char) == 127 for char in path)):
        raise PatchError("Conf library path must be an absolute path to libdtsaudio.so")
    if re.fullmatch(r"[/A-Za-z0-9_.+-]+", path):
        return path
    return '"' + path.replace("\\", "\\\\").replace('"', '\\"') + '"'


def patch_conf(source: str, library_path: str = DEFAULT_CONF_LIBRARY) -> str:
    """Merge legacy conf by insertion, preserving every existing character."""
    rendered_path = _conf_library_path(library_path)
    root = _parse_conf(source)
    libraries, effects = _one_block(root, "libraries"), _one_block(root, "effects")
    if libraries is None and effects is None:
        raise PatchError("No libraries or effects section in audio effects conf")
    postprocess = _one_block(root, "output_session_processing")
    library = _one_block(libraries, LIBRARY_NAME)
    effect = _one_block(effects, EFFECT_NAME)
    if library is not None and _field_value(library, "path") != library_path:
        raise PatchError("The dtsaudio library name is already used differently")
    if libraries is not None:
        for entry in _walk_blocks(libraries):
            for value in entry.children:
                if (isinstance(value, _Field) and value.name == "path"
                        and _is_dts_path(value.value) and entry is not library):
                    raise PatchError("libdtsaudio.so is already registered under another name")
    if effect is not None:
        if (_field_value(effect, "library") != LIBRARY_NAME
                or not _same_uuid(_field_value(effect, "uuid"))):
            raise PatchError("The dts_audio effect name is already used differently")
    if effects is not None:
        for entry in _walk_blocks(effects):
            for value in entry.children:
                if (isinstance(value, _Field) and value.name == "uuid"
                        and _same_uuid(value.value) and entry is not effect):
                    raise PatchError("The DTS UUID is already registered under another name")
            if entry.name == EFFECT_NAME and entry is not effect:
                raise PatchError("The DTS effect name is already used by a nested block")
    music = _one_block(postprocess, "music")
    apply = _one_block(music, EFFECT_NAME)
    inserts: dict[int, list[str]] = {}
    newline = "\r\n" if "\r\n" in source else "\n"

    def insert(parent: _Block, lines: list[str]) -> None:
        indent = "  " * (parent.depth + 1)
        text = newline + newline.join(indent + line for line in lines) + newline
        inserts.setdefault(parent.close, []).append(text)

    if library is None:
        lines = [f"{LIBRARY_NAME} {{", f"  path {rendered_path}", "}"]
        if libraries is None:
            insert(root, ["libraries {"] + ["  " + line for line in lines] + ["}"])
        else:
            insert(libraries, lines)
    if effect is None:
        lines = [f"{EFFECT_NAME} {{", f"  library {LIBRARY_NAME}",
                 f"  uuid {EFFECT_UUID}", "}"]
        if effects is None:
            insert(root, ["effects {"] + ["  " + line for line in lines] + ["}"])
        else:
            insert(effects, lines)
    if apply is None:
        if postprocess is None:
            insert(root, ["output_session_processing {", "  music {",
                          f"    {EFFECT_NAME} {{}}", "  }", "}"])
        elif music is None:
            insert(postprocess, ["music {", f"  {EFFECT_NAME} {{}}", "}"])
        else:
            insert(music, [f"{EFFECT_NAME} {{}}"])
    result = source
    for position in sorted(inserts, reverse=True):
        result = result[:position] + "".join(inserts[position]) + result[position:]
    return result


def patch_file(input_path: Path, output_path: Path, file_format: str | None = None,
               library_path: str = DEFAULT_CONF_LIBRARY) -> None:
    source = input_path.read_bytes().decode("utf-8")
    if file_format is None:
        file_format = "xml" if source.lstrip("\ufeff \r\n\t").startswith("<") else "conf"
    if file_format == "xml":
        result = patch_xml(source)
    elif file_format == "conf":
        result = patch_conf(source, library_path)
    else:
        raise PatchError(f"Unsupported format: {file_format}")
    # Validate the whole input before creating a temporary file. A conflict must
    # never replace an existing output, even when input and output are identical.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output_path.parent, prefix=output_path.name + ".",
                                         suffix=".tmp", delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(result.encode("utf-8"))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, output_path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--format", choices=("xml", "conf"))
    parser.add_argument("--library-path", default=DEFAULT_CONF_LIBRARY,
                        help="Absolute vendor library path for legacy conf only")
    args = parser.parse_args(argv)
    try:
        patch_file(args.input, args.output, args.format, args.library_path)
    except (PatchError, OSError, UnicodeError) as error:
        print(f"DTS configuration merge failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
