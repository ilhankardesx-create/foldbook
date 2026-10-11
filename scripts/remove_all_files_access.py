from pathlib import Path
import re

p = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
s = p.read_text()

for line in [
    'import android.os.Build\n',
    'import android.os.Environment\n',
    'import android.provider.Settings\n',
]:
    s = s.replace(line, '')

s, count = re.subn(
    r'\n    fun scanDeviceBooks\(\) \{.*?\n    \}\n\n    fun openBook\(',
    '\n    fun openBook(',
    s,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit(f'scanDeviceBooks block removal count={count}')

s, count = re.subn(
    r'\n    val allFilesAccessLauncher = rememberLauncherForActivityResult\(.*?\n    \}\n\n    fun scanDeviceBooksWithPermission\(\) \{.*?\n    \}\n\n    LaunchedEffect\(Unit\)',
    '\n    LaunchedEffect(Unit)',
    s,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit(f'all-files permission flow removal count={count}')

s = s.replace('            onScanDevice = ::scanDeviceBooksWithPermission,\n', '')
s = s.replace('    onScanDevice: () -> Unit,\n', '')

button = '''\n                            Button(\n                                onClick = onScanDevice,\n                                enabled = !isLoading,\n                                modifier = Modifier.fillMaxWidth()\n                            ) {\n                                Text(\n                                    if (isLoading) {\n                                        "Taranıyor…"\n                                    } else {\n                                        "Cihazdaki Bütün Kitapları Bul"\n                                    },\n                                    textAlign = TextAlign.Center\n                                )\n                            }\n'''
if button not in s:
    raise SystemExit('device scan button block not found')
s = s.replace(button, '\n', 1)

for forbidden in [
    'scanDeviceBooks',
    'allFilesAccessLauncher',
    'ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION',
    'ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION',
    'Environment.isExternalStorageManager',
    'onScanDevice',
    'Cihazdaki Bütün Kitapları Bul',
]:
    if forbidden in s:
        raise SystemExit(f'forbidden token still present: {forbidden}')

p.write_text(s)
