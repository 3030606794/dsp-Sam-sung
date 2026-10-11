// Convert the supplied vector artwork to fixed black-on-white Android resources.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const svg = fs.readFileSync(path.join(root, 'artwork/DSP音效.svg'), 'utf8');
const paths = [...svg.matchAll(/<path\b[^>]*\bd="([^"]+)"/g)].map(match => match[1]);
if (!paths.length) throw new Error('No paths found in supplied icon');
const vector = white => `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
${white ? '    <path android:fillColor="#FFFFFF" android:pathData="M0,0h108v108h-108z"/>\n' : ''}    <group android:scaleX="0.05" android:scaleY="0.05" android:translateX="24.75" android:translateY="28.4">
${paths.map(p => `        <path android:fillColor="#000000" android:pathData="${p}"/>`).join('\n')}
    </group>
</vector>
`;
const write = (relative, data) => {
    const file = path.join(root, relative);
    fs.mkdirSync(path.dirname(file), {recursive: true});
    fs.writeFileSync(file, data);
};
write('app/src/main/res/drawable/dsp_launcher_foreground.xml', vector(false));
write('app/src/main/res/mipmap-anydpi/ic_dsp_launcher.xml', vector(true));
write('app/src/main/res/drawable/dsp_launcher_background.xml', `<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#FFFFFF"/>
</shape>
`);
write('app/src/main/res/mipmap-anydpi-v26/ic_dsp_launcher.xml', `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/dsp_launcher_background"/>
    <foreground android:drawable="@drawable/dsp_launcher_foreground"/>
</adaptive-icon>
`);
console.log('Generated fixed black-on-white launcher vectors; no themed monochrome layer.');
