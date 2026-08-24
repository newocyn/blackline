#!/usr/bin/env python3
from pathlib import Path

ROOT = Path("termux-app")


def replace_required(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Required text not found in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


# Visible branding. Keep the internal com.termux package/prefix for package compatibility.
strings = ROOT / "app/src/main/res/values/strings.xml"
replace_required(strings, '<!ENTITY TERMUX_APP_NAME "Termux">', '<!ENTITY TERMUX_APP_NAME "BLACKLINE">')

build_gradle = ROOT / "app/build.gradle"
replace_required(build_gradle, 'manifestPlaceholders.TERMUX_APP_NAME = "Termux"', 'manifestPlaceholders.TERMUX_APP_NAME = "BLACKLINE"')

# Install a BLACKLINE initialization asset after Termux bootstrap completes.
activity = ROOT / "app/src/main/java/com/termux/app/TermuxActivity.java"
text = activity.read_text(encoding="utf-8")

old_import = "import java.util.Arrays;"
new_import = """import java.util.Arrays;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;"""
if old_import not in text:
    raise SystemExit("TermuxActivity import anchor not found")
text = text.replace(old_import, new_import, 1)

anchor = "    @Override\n    public void onStart() {"
method = r'''    private void installBlacklineExperience() {
        File prefixBash = new File(getFilesDir(), "usr/bin/bash");
        if (!prefixBash.exists()) return;

        File home = new File(getFilesDir(), "home");
        if (!home.exists() && !home.mkdirs()) return;

        File initScript = new File(home, ".blackline-init.sh");
        try (InputStream in = getAssets().open("blackline-init.sh");
             FileOutputStream out = new FileOutputStream(initScript, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            initScript.setExecutable(true, false);

            Process process = new ProcessBuilder(prefixBash.getAbsolutePath(), initScript.getAbsolutePath())
                .redirectErrorStream(true)
                .start();
            process.waitFor();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to initialize BLACKLINE experience", e);
        }
    }

'''
if anchor not in text:
    raise SystemExit("TermuxActivity method anchor not found")
text = text.replace(anchor, method + anchor, 1)

# Ensure an existing bootstrap gets branded too.
service_anchor = "        mTermuxService = ((TermuxService.LocalBinder) service).service;\n"
if service_anchor not in text:
    raise SystemExit("Service connection anchor not found")
text = text.replace(service_anchor, service_anchor + "\n        installBlacklineExperience();\n", 1)

# Ensure a brand-new bootstrap gets initialized before the first shell session.
bootstrap_anchor = "                    if (mTermuxService == null) return; // Activity might have been destroyed.\n                    try {"
if bootstrap_anchor not in text:
    raise SystemExit("Bootstrap callback anchor not found")
text = text.replace(
    bootstrap_anchor,
    "                    if (mTermuxService == null) return; // Activity might have been destroyed.\n                    installBlacklineExperience();\n                    try {",
    1,
)
activity.write_text(text, encoding="utf-8")

assets = ROOT / "app/src/main/assets"
assets.mkdir(parents=True, exist_ok=True)

init_script = r'''#!/data/data/com.termux/files/usr/bin/bash
set -u

PREFIX=/data/data/com.termux/files/usr
HOME=/data/data/com.termux/files/home
mkdir -p "$HOME/.termux" "$PREFIX/bin"

cat > "$HOME/.termux/colors.properties" <<'EOF'
background=#05070A
foreground=#EAFBFF
cursor=#00E5FF
color0=#0A0D11
color1=#FF466B
color2=#4DFFB8
color3=#FFD166
color4=#00B8FF
color5=#C77DFF
color6=#00E5FF
color7=#EAFBFF
color8=#53606B
color9=#FF6B87
color10=#72FFC8
color11=#FFE29A
color12=#62D2FF
color13=#D8A8FF
color14=#69F5FF
color15=#FFFFFF
EOF

cat > "$HOME/.termux/termux.properties" <<'EOF'
extra-keys = [['ESC','CTRL','ALT','TAB','HOME','UP','END','PGUP'],['FN','/','-','LEFT','DOWN','RIGHT','PGDN','KEYBOARD']]
terminal-toolbar-height=2.0
EOF

MARKER='# BLACKLINE // managed shell profile'
if ! grep -qF "$MARKER" "$HOME/.bashrc" 2>/dev/null; then
cat >> "$HOME/.bashrc" <<'EOF'

# BLACKLINE // managed shell profile
export BLACKLINE=1
export PS1='\[\e[38;5;45m\]blackline\[\e[38;5;240m\]::\[\e[38;5;252m\]\w\[\e[0m\] \$ '
alias cls='clear'
alias ll='ls -lah --color=auto'
alias kali='nethunter'
alias nh='nethunter'

if [ -z "${BLACKLINE_BANNER_SHOWN:-}" ]; then
  export BLACKLINE_BANNER_SHOWN=1
  printf '\033[38;5;45mBLACKLINE // DECK\033[0m\n'
  printf '\033[38;5;240mANDROID NODE // LOCAL SHELL\033[0m\n'
  printf '\033[38;5;240mType \033[38;5;45mblackline-help\033[38;5;240m for deck commands.\033[0m\n\n'
fi
EOF
fi

cat > "$PREFIX/bin/blackline-help" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
printf '\033[38;5;45mBLACKLINE // COMMAND INDEX\033[0m\n\n'
printf '  blackline-info   Device + Linux telemetry\n'
printf '  blackline-kali   Install/start Kali NetHunter Rootless\n'
printf '  nethunter / nh   Enter Kali after installation\n'
printf '  pkg              Install Android-native Linux packages\n'
printf '  termux-setup-storage  Grant shared storage access\n'
printf '\nStandard shell tools, ssh, git, python, clang and more can be installed with pkg.\n'
EOF
chmod 700 "$PREFIX/bin/blackline-help"

cat > "$PREFIX/bin/blackline-info" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cyan='\033[38;5;45m'; dim='\033[38;5;240m'; reset='\033[0m'
printf "${cyan}BLACKLINE // NODE TELEMETRY${reset}\n\n"
printf "${dim}DEVICE${reset}      %s %s\n" "$(getprop ro.product.manufacturer)" "$(getprop ro.product.model)"
printf "${dim}ANDROID${reset}     %s (SDK %s)\n" "$(getprop ro.build.version.release)" "$(getprop ro.build.version.sdk)"
printf "${dim}ARCH${reset}        %s\n" "$(uname -m)"
printf "${dim}KERNEL${reset}      %s\n" "$(uname -r)"
printf "${dim}HOST${reset}        %s\n" "$(hostname 2>/dev/null || echo android)"
printf "${dim}USER${reset}        %s\n" "$(id -un 2>/dev/null || id -u)"
printf "${dim}PREFIX${reset}      %s\n" "$PREFIX"
printf "${dim}HOME${reset}        %s\n" "$HOME"
printf "${dim}STORAGE${reset}     %s\n" "$(df -h "$HOME" 2>/dev/null | tail -1 | awk '{print $3" / "$2" used ("$5")"}')"
printf "${dim}KALI${reset}        %s\n" "$(command -v nethunter >/dev/null 2>&1 && echo READY || echo NOT INSTALLED)"
EOF
chmod 700 "$PREFIX/bin/blackline-info"

cat > "$PREFIX/bin/blackline-kali" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -e
cyan='\033[38;5;45m'; reset='\033[0m'
printf "${cyan}BLACKLINE // KALI ENGINE${reset}\n\n"
if command -v nethunter >/dev/null 2>&1; then
  echo 'Kali NetHunter is installed. Launching...'
  exec nethunter
fi
cat <<'TXT'
This installs Kali NetHunter Rootless using Kali's official Android installer.
No bootloader unlock or Android root is required.
The Kali filesystem is large; Wi-Fi and several GB of free storage are recommended.
TXT
printf '\nContinue? [y/N] '
read -r answer
case "$answer" in
  y|Y|yes|YES) ;;
  *) echo 'Cancelled.'; exit 0 ;;
esac
pkg update -y
pkg install -y wget
termux-setup-storage || true
cd "$HOME"
wget -O install-nethunter-termux https://offs.ec/2MceZWr
chmod 700 install-nethunter-termux
printf '\nDownloaded the official NetHunter Rootless installer. Starting it now...\n\n'
exec ./install-nethunter-termux
EOF
chmod 700 "$PREFIX/bin/blackline-kali"

cat > "$PREFIX/etc/motd" <<'EOF'
BLACKLINE // DECK
Android Linux command environment

blackline-help  command index
blackline-info  node telemetry
blackline-kali  Kali NetHunter Rootless
EOF

rm -f "$HOME/.blackline-init.sh" 2>/dev/null || true
exit 0
'''
(assets / "blackline-init.sh").write_text(init_script, encoding="utf-8")

print("BLACKLINE patches applied successfully")
