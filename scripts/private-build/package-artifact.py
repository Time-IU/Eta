"""Check that the APK is valid and unsigned, then emit a checksummed private artifact."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[2]
apks = list((root / 'app/build/outputs/apk/release').glob('*.apk'))
if len(apks) != 1:
    raise SystemExit(f'Expected one Release APK; found {len(apks)}')
apk = apks[0]
with zipfile.ZipFile(apk) as archive:
    if archive.testzip() is not None:
        raise SystemExit('APK ZIP integrity check failed')
    for required in ['AndroidManifest.xml', 'classes.dex', 'resources.arsc']:
        if required not in archive.namelist():
            raise SystemExit(f'APK is missing {required}')
    if any(name.upper().startswith('META-INF/') and name.upper().endswith(('.RSA', '.DSA', '.EC', '.SF'))
           for name in archive.namelist()):
        raise SystemExit('Unexpected JAR signing material in APK')
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
tools = sdk / 'build-tools/36.0.0'
subprocess.run([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], check=True, stdout=subprocess.DEVNULL)
result = subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', str(apk)], capture_output=True, text=True)
verification_text = result.stdout + result.stderr
if result.returncode == 0:
    raise SystemExit('Refusing to upload a signed APK')
if not any(message in verification_text for message in ['Missing META-INF/MANIFEST.MF', 'No signatures']):
    raise SystemExit('Unexpected signature verification failure: ' + verification_text)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
if sha != os.environ.get('GITHUB_SHA', sha):
    raise SystemExit('Checked-out commit does not match workflow commit')
output = root / 'out/private-build'
output.mkdir(parents=True, exist_ok=True)
name = f'Eta-private-{sha[:12]}-unsigned.apk'
shutil.copyfile(apk, output / name)
digest = hashlib.sha256((output / name).read_bytes()).hexdigest()
(output / 'SHA256SUMS.txt').write_text(f'{digest}  {name}\n')
metadata = json.loads((apk.parent / 'output-metadata.json').read_text())
source = json.loads((root / 'docs/private-build/source-provenance.json').read_text())
report = {
    'repository': os.environ.get('GITHUB_REPOSITORY', 'Time-IU/Eta-build'),
    'sourceCommit': sha,
    'workflowRun': os.environ.get('GITHUB_RUN_ID'),
    'filename': name,
    'sha256': digest,
    'bytes': (output / name).stat().st_size,
    'versionName': metadata['elements'][0]['versionName'],
    'versionCode': metadata['elements'][0]['versionCode'],
    'unsignedVerified': True,
    'zipIntegrityVerified': True,
    'referenceSourceBase': source['sourceBase'],
    'signaturePrivateKeysUsed': False,
    'testsRunByThisWorkflow': True,
    'deviceAcceptanceRunByThisWorkflow': False,
    'licenseReviewStatus': 'existing combined-source distribution questions unresolved; private storage is not certification',
}
(output / 'build-manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
shutil.copyfile(root / 'docs/private-build/CHANGELOG-token-economy.md', output / 'CHANGELOG.md')
(output / 'README.txt').write_text(
    'Private unsigned APK. Cannot be installed until signed locally.\n'
    'Use the existing custom certificate for upgrade compatibility; never upload its private key.\n'
    'Unsigned CI output is not byte-identical to the locally signed reference APK.\n'
    'No public Release, tag, installation or device-acceptance claim.\n'
)
print(json.dumps(report, ensure_ascii=False, indent=2))
