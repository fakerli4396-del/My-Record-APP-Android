"""Verify the M0 encryption sample between two isolated API 37 AVDs."""
import argparse
import base64
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--sender", default="emulator-5580")
    parser.add_argument("--receiver", default="emulator-5582")
    parser.add_argument("--full", action="store_true", help="Verify a complete Room ledger and its Windows-imported media")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    adb = args.sdk / "platform-tools" / "adb.exe"
    package = "com.myrecord.program"
    runner = package + ".test/androidx.test.runner.AndroidJUnitRunner"
    test = package + (".FullBackupDeviceTest" if args.full else ".BackupCipherDeviceTest")
    stem = "m4" if args.full else "m0"

    def call(serial, *arguments):
        return subprocess.run([str(adb), "-s", serial, *arguments],
                              capture_output=True, check=True).stdout

    assert args.sender != args.receiver
    for serial in (args.sender, args.receiver):
        assert serial.startswith("emulator-")
        assert call(serial, "emu", "avd", "name").startswith(b"MyRecord_")
        assert call(serial, "shell", "getprop", "ro.build.version.sdk").strip() == b"37"
        assert call(serial, "shell", "getprop", "sys.boot_completed").strip() == b"1"
        call(serial, "shell", "cmd", "connectivity", "airplane-mode", "enable")
        call(serial, "shell", "svc", "wifi", "disable")
        assert call(serial, "shell", "settings", "get", "global", "airplane_mode_on").strip() == b"1"
        assert call(serial, "shell", "settings", "get", "global", "wifi_on").strip() == b"0"
        for apk in ("debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"):
            print(call(serial, "install", "-r", str(root / "app/build/outputs/apk" / apk)).decode(), end="")

    def verify(serial, receive):
        output = call(serial, "shell", "am", "instrument", "-w", "-e", "class", test,
                      "-e", "receive", receive, runner)
        print(output.decode(errors="replace"))
        assert b"OK (1 test)" in output, f"Encryption verification failed on {serial}"

    verify(args.sender, "false")
    transferred = call(args.sender, "shell", "run-as", package, "base64", f"files/{stem}-transfer-out.mrbackup")
    encrypted = base64.b64decode(b"".join(transferred.split()), validate=True)
    assert encrypted.startswith(b"MRBK\x01")
    token = base64.b64encode(encrypted).decode("ascii")
    path = f"files/{stem}-transfer-in.mrbackup"
    for start in range(0, len(token), 4096):
        redirection = ">" if start == 0 else ">>"
        call(args.receiver, "shell", "run-as", package, "sh", "-c",
             f"'mkdir -p files && printf %s {token[start:start + 4096]} | base64 -d {redirection} {path}'")
    received = call(args.receiver, "shell", "run-as", package, "base64", path)
    assert base64.b64decode(b"".join(received.split()), validate=True) == encrypted
    verify(args.receiver, "true")
    print(f"PASS {len(encrypted)}-byte encrypted sample across two offline API 37 installations")


if __name__ == "__main__":
    main()
