import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from xml.dom import minidom


SCRIPT = Path(__file__).resolve().parents[1] / "patch_effects.py"
SPEC = importlib.util.spec_from_file_location("patch_effects", SCRIPT)
merger = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = merger
SPEC.loader.exec_module(merger)

UUID = merger.EFFECT_UUID
FUSION_UUID = "30306067-9400-4d53-9000-000000000001"

XML = f'''<?xml version="1.0" encoding="UTF-8"?>
<?vendor retain-this?>
<audio_effects_conf version="2.0" vendor="samsung">
  <!-- OEM and Fusion registrations must survive -->
  <libraries vendor="keep"><library name="dsp_fusion" path="libdspfusion.so"/>
    <library name="soundalive" path="libSoundAlive.so" custom="yes"/></libraries>
  <effects><effect name="dsp_fusion" library="dsp_fusion" uuid="{FUSION_UUID}"/>
    <effectProxy name="oem_proxy" library="proxy" uuid="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa">
      <libsw library="soundalive" uuid="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"/>
    </effectProxy></effects>
  <preprocess><stream type="voice_communication"><apply effect="aec"/></stream></preprocess>
  <postprocess custom="keep"><stream type="music" mode="original">
    <apply effect="soundalive" custom="retained"/><apply effect="dsp_fusion"/>
  </stream><stream type="ring"><apply effect="soundalive"/></stream></postprocess>
</audio_effects_conf>'''

CONF = f'''# top comment with {{ brace }}
libraries {{
  dsp_fusion {{ path /vendor/lib64/soundfx/libdspfusion.so }}
  oem {{ path "/vendor/lib64/soundfx/lib OEM {{v2}}.so" }} // keep {{ }}
}}
effects {{
  dsp_fusion {{ library dsp_fusion uuid {FUSION_UUID} }}
  oem_proxy {{ library proxy uuid aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
    libsw {{ library oem uuid bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb }}
  }}
}}
output_session_processing {{
  music {{ oem_proxy {{}} dsp_fusion {{}} /* extra }} {{ */ }}
  ring {{ oem_proxy {{}} }}
}}
'''


def xml_projection(text):
    """Original subtrees after removing only the inserted DTS nodes/whitespace."""
    document = minidom.parseString(text)
    for element in list(document.getElementsByTagName("*")):
        if (element.getAttribute("name") in ("dtsaudio", "dts_audio")
                or element.getAttribute("effect") == "dts_audio"):
            element.parentNode.removeChild(element)
    def strip(node):
        for child in list(node.childNodes):
            if child.nodeType == child.TEXT_NODE and not child.data.strip():
                node.removeChild(child)
            else:
                strip(child)
    strip(document)
    return document.toxml()


class XmlMergeTest(unittest.TestCase):
    def test_preserves_oem_fusion_attributes_comments_and_only_adds_music(self):
        result = merger.patch_xml(XML)
        self.assertEqual(xml_projection(XML), xml_projection(result))
        self.assertEqual(result, merger.patch_xml(result))
        document = minidom.parseString(result)
        for stream in document.getElementsByTagName("stream"):
            dts = [entry for entry in stream.getElementsByTagName("apply")
                   if entry.getAttribute("effect") == "dts_audio"]
            self.assertEqual(1 if stream.getAttribute("type") == "music" else 0, len(dts))

    def test_creates_missing_tables_and_music_without_touching_other_streams(self):
        for source in [
            '<audio_effects_conf version="2.0"/>',
            '<audio_effects_conf><libraries/><effects/><postprocess>'
            '<stream type="ring"><apply effect="oem"/></stream></postprocess></audio_effects_conf>',
        ]:
            with self.subTest(source=source):
                result = merger.patch_xml(source)
                self.assertEqual(result, merger.patch_xml(result))
                self.assertEqual(1, result.count('effect="dts_audio"'))
                self.assertEqual(1, result.count('uuid="' + UUID + '"'))

    def test_namespaced_config_retains_namespace_for_inserted_nodes(self):
        for source in [
            '<audio_effects_conf xmlns="urn:vendor:effects"><libraries/><effects/></audio_effects_conf>',
            '<ae:audio_effects_conf xmlns:ae="urn:vendor:effects"><ae:libraries/>'
            '<ae:effects/></ae:audio_effects_conf>',
        ]:
            result = merger.patch_xml(source)
            document = minidom.parseString(result)
            for element in document.getElementsByTagName("*"):
                self.assertEqual("urn:vendor:effects", element.namespaceURI)
            self.assertEqual(result, merger.patch_xml(result))

    def test_accepts_existing_matching_nodes_and_uppercase_uuid_without_changes(self):
        result = merger.patch_xml(XML).replace(UUID, UUID.upper())
        self.assertEqual(result, merger.patch_xml(result))

    def test_does_not_treat_foreign_namespace_or_proxy_as_matching_registration(self):
        for source in [
            '<audio_effects_conf xmlns:v="urn:vendor"><libraries>'
            '<v:library name="dtsaudio" path="libdtsaudio.so"/></libraries><effects/></audio_effects_conf>',
            '<audio_effects_conf><libraries/><effects><effectProxy name="oem">'
            f'<libsw uuid="{UUID}" library="dtsaudio"/></effectProxy></effects></audio_effects_conf>',
        ]:
            with self.subTest(source=source):
                with self.assertRaises(merger.PatchError):
                    merger.patch_xml(source)

    def test_conflicts_never_overwrite_existing_values(self):
        complete = merger.patch_xml(XML)
        conflicts = [
            complete.replace('name="dtsaudio" path="libdtsaudio.so"',
                             'name="dtsaudio" path="libother.so"'),
            complete.replace('name="dtsaudio"', 'name="dts_alias"'),
            complete.replace('name="dts_audio"', 'name="other_dts"'),
            complete.replace('library="dtsaudio" uuid=', 'library="other" uuid='),
            complete.replace(UUID, FUSION_UUID),
            complete.replace('</libraries>', '<library name="dtsaudio" path="libdtsaudio.so"/></libraries>'),
            complete.replace('</effects>', f'<effect name="other" uuid="{UUID}" library="dtsaudio"/></effects>'),
            complete.replace('<apply effect="dts_audio"/>', '<apply effect="dts_audio"/><apply effect="dts_audio"/>'),
            complete.replace('</postprocess>', '<stream type="music"/></postprocess>'),
        ]
        for source in conflicts:
            with self.subTest(source=source):
                with self.assertRaises(merger.PatchError):
                    merger.patch_xml(source)

    def test_rejects_malformed_xml_dtd_wrong_root_and_duplicate_tables(self):
        for source in ['<audio_effects_conf><effects></audio_effects_conf>',
                       '<other/>', '<audio_effects_conf><libraries/><libraries/></audio_effects_conf>',
                       '<!DOCTYPE audio_effects_conf [<!ENTITY x "text">]><audio_effects_conf/>']:
            with self.subTest(source=source):
                with self.assertRaises(merger.PatchError):
                    merger.patch_xml(source)


class ConfMergeTest(unittest.TestCase):
    def test_preserves_original_text_comments_quoted_braces_and_oem_processing(self):
        result = merger.patch_conf(CONF)
        self.assertEqual(result, merger.patch_conf(result))
        # Only inserted spans may differ, including whitespace within old blocks.
        additions = [
            '\n  dtsaudio {\n    path /vendor/lib64/soundfx/libdtsaudio.so\n  }\n',
            '\n  dts_audio {\n    library dtsaudio\n    uuid ' + UUID + '\n  }\n',
            '\n    dts_audio {}\n',
        ]
        original = result
        for addition in additions:
            self.assertIn(addition, original)
            original = original.replace(addition, '', 1)
        self.assertEqual(CONF, original)
        self.assertIn('ring { oem_proxy {} }', result)

    def test_creates_missing_sections_with_minified_and_crlf_conf(self):
        for source in ['libraries{}effects{}', 'libraries {}\r\neffects {}\r\n',
                       'libraries { oem { path x } }',
                       'effects {} output_session_processing { ring { oem{} } }']:
            with self.subTest(source=source):
                result = merger.patch_conf(source)
                self.assertEqual(result, merger.patch_conf(result))
                self.assertIn('music {', result)
                self.assertIn('dts_audio {}', result)
                if '\r\n' in source:
                    self.assertNotIn('\n', result.replace('\r\n', ''))

    def test_custom_32_bit_library_path_and_existing_uuid_case(self):
        path = '/vendor/lib/soundfx/libdtsaudio.so'
        result = merger.patch_conf(CONF, path)
        self.assertIn('path ' + path, result)
        result = result.replace(UUID, UUID.upper())
        self.assertEqual(result, merger.patch_conf(result, path))

    def test_comment_markers_and_escaped_quotes_inside_strings_are_preserved(self):
        source = r'''libraries { oem { path "lib\\name\"{#/*literal*/}.so" } } effects {}'''
        result = merger.patch_conf(source)
        self.assertIn(r'''oem { path "lib\\name\"{#/*literal*/}.so" }''', result)
        self.assertEqual(result, merger.patch_conf(result))

    def test_conflicts_in_names_paths_nested_uuid_and_duplicate_music(self):
        complete = merger.patch_conf(CONF)
        conflicts = [
            complete.replace('/vendor/lib64/soundfx/libdtsaudio.so', '/vendor/lib64/soundfx/other.so'),
            complete.replace('dtsaudio {', 'other_lib {'),
            complete.replace('dts_audio {\n', 'other_effect {\n'),
            complete.replace('library dtsaudio', 'library other'),
            complete.replace(UUID, FUSION_UUID),
            complete + 'libraries {}',
            complete.replace('ring {', 'music {'),
            complete.replace('dts_audio {}', 'dts_audio {} dts_audio {}'),
            complete.replace('libsw { library oem uuid bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb }',
                             'libsw { library oem uuid ' + UUID + ' }'),
        ]
        for source in conflicts:
            with self.subTest(source=source):
                with self.assertRaises(merger.PatchError):
                    merger.patch_conf(source)

    def test_rejects_unbalanced_braces_unterminated_quotes_comments_and_bad_path(self):
        for source in ['libraries { effects {}', 'libraries {} }',
                       'libraries { oem { path "unterminated } }',
                       'libraries {} /* unfinished', 'libraries { path }', 'not_audio x', '']:
            with self.subTest(source=source):
                with self.assertRaises(merger.PatchError):
                    merger.patch_conf(source)
        for path in ['libdtsaudio.so', '/vendor/../lib/libdtsaudio.so', '/vendor/lib/other.so',
                     '/vendor/lib/\nlibdtsaudio.so']:
            with self.subTest(path=path):
                with self.assertRaises(merger.PatchError):
                    merger.patch_conf(CONF, path)


class FileAndCliTest(unittest.TestCase):
    def test_cli_generates_file_and_is_idempotent_when_reused_in_place(self):
        with tempfile.TemporaryDirectory() as folder:
            source, target = Path(folder) / 'input.xml', Path(folder) / 'output.xml'
            source.write_bytes(XML.encode('utf-8'))
            first = subprocess.run([sys.executable, str(SCRIPT), str(source), str(target)],
                                   capture_output=True, text=True)
            self.assertEqual(0, first.returncode, first.stderr)
            generated = target.read_bytes()
            second = subprocess.run([sys.executable, str(SCRIPT), str(target), str(target)],
                                    capture_output=True, text=True)
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual(generated, target.read_bytes())
            self.assertEqual(XML.encode('utf-8'), source.read_bytes())

    def test_conflict_malformed_utf8_and_atomic_replace_failure_keep_existing_output(self):
        with tempfile.TemporaryDirectory() as folder:
            source, target = Path(folder) / 'input.xml', Path(folder) / 'output.xml'
            target.write_bytes(b'previous result')
            for bad in [b'<broken>', b'\xff',
                        merger.patch_xml(XML).replace(UUID, FUSION_UUID).encode('utf-8')]:
                source.write_bytes(bad)
                result = subprocess.run([sys.executable, str(SCRIPT), str(source), str(target)],
                                        capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(b'previous result', target.read_bytes())
            source.write_bytes(XML.encode('utf-8'))
            with patch.object(merger.os, 'replace', side_effect=OSError('simulated write failure')):
                with self.assertRaises(OSError):
                    merger.patch_file(source, target)
            self.assertEqual(b'previous result', target.read_bytes())
            self.assertEqual(['input.xml', 'output.xml'], sorted(p.name for p in Path(folder).iterdir()))


if __name__ == '__main__':
    unittest.main()
