#!/usr/bin/env python3
"""
Sync 4K Wallpapers to Clean Your TV Launcher (Xiaomi TV)

STRICT REQUIREMENTS ENFORCED:
1. ONLY 4K RESOLUTION: At least 3840 x 2160 pixels (UHD).
2. ONLY JPG & PNG FORMATS: Only .jpg, .jpeg, and .png (WebP, GIF, and all others strictly rejected).
3. NO DUPLICATES: Checks both MD5 content hash and filename against all wallpapers on the TV.
4. ANDROID 14 SAFE: Stages pushes through /data/local/tmp to avoid Android 14 sdcardfs fchown restrictions.
5. AUTO RELOAD: Reloads Clean Your TV launcher so new wallpapers appear immediately in rotation.
"""

import sys
import os
import re
import hashlib
import subprocess
import tkinter as tk
from tkinter import filedialog

TV_HOST_DEFAULT = "192.168.1.36:5555"
TV_WALLPAPER_DIR = "/sdcard/Android/data/com.gathin.tvhome/files/wallpapers"
TV_TMP_DIR = "/data/local/tmp"
MAX_SIZE_BYTES = 10 * 1024 * 1024  # 10 MB CleanYourTV wallpaper limit

# STRICT: JPG and PNG only. WebP and others are completely rejected.
VALID_EXTENSIONS = {".jpg", ".jpeg", ".png"}

# STRICT: Minimum 4K UHD Resolution
MIN_4K_WIDTH = 3840
MIN_4K_HEIGHT = 2160


def wait_exit(msg="\nPress Enter to close..."):
    """Pause before exiting if run interactively (so console window stays open)."""
    if sys.stdin.isatty():
        try:
            input(msg)
        except (EOFError, KeyboardInterrupt):
            pass


def get_tv_host():
    for arg in sys.argv[1:]:
        if arg.startswith("--host="):
            return arg.split("=", 1)[1]
    return os.environ.get("TV_HOST", TV_HOST_DEFAULT)


def pick_folder_dialog():
    """Show a Windows native folder picker dialog."""
    root = tk.Tk()
    root.withdraw()
    root.attributes('-topmost', True)
    folder = filedialog.askdirectory(
        title="Select Folder with 4K Wallpapers (JPG / PNG only) for Clean Your TV"
    )
    root.destroy()
    return folder if folder else None


def get_image_dimensions(file_path):
    """
    Get (width, height) of an image file.
    Uses Pillow with a fallback for PNG header reading.
    """
    try:
        from PIL import Image
        with Image.open(file_path) as img:
            return img.size
    except Exception:
        pass

    # Header-level fallback for PNG
    try:
        with open(file_path, "rb") as f:
            header = f.read(24)
            if header.startswith(b"\x89PNG\r\n\x1a\n"):
                w = int.from_bytes(header[16:20], "big")
                h = int.from_bytes(header[20:24], "big")
                return w, h
    except Exception:
        pass

    return None, None


def is_4k(width, height):
    """Verify if the image is at least 4K UHD (3840x2160)."""
    if width is None or height is None:
        return False
    return width >= MIN_4K_WIDTH and height >= MIN_4K_HEIGHT


def compute_md5(file_path):
    """Compute MD5 hash of a local file."""
    h = hashlib.md5()
    try:
        with open(file_path, "rb") as f:
            while chunk := f.read(65536):
                h.update(chunk)
        return h.hexdigest().lower()
    except Exception as e:
        print(f"  [!] Error reading {file_path}: {e}")
        return None


def sanitize_filename(name):
    """
    Ensure filename contains only [A-Za-z0-9_.-] as required by CleanYourTV.
    Replaces spaces and invalid characters with underscores.
    """
    base, ext = os.path.splitext(name)
    clean_base = re.sub(r"[^A-Za-z0-9_.-]", "_", base)
    clean_base = re.sub(r"_+", "_", clean_base).strip("_.")
    clean_ext = ext.lower()
    if not clean_base:
        clean_base = "wallpaper"
    return clean_base + clean_ext


def run_adb(cmd_args, tv_host):
    """Run an adb command targeting the TV."""
    base_cmd = ["adb", "-s", tv_host]
    full_cmd = base_cmd + cmd_args
    try:
        return subprocess.run(full_cmd, capture_output=True, text=True, check=False)
    except FileNotFoundError:
        print("\n[ERROR] 'adb' command not found. Please ensure Android platform-tools or ADB is on your PATH.")
        sys.exit(1)


def connect_tv(tv_host):
    """Ensure TV is connected via ADB."""
    print(f"Connecting to TV at {tv_host}...")
    res = subprocess.run(["adb", "connect", tv_host], capture_output=True, text=True)
    if "connected" in res.stdout.lower() or "already connected" in res.stdout.lower():
        print("  ✓ Connected successfully.")
        return True
    else:
        # Check if device is in adb devices list
        devices = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
        if tv_host in devices:
            print("  ✓ TV is online.")
            return True
        print(f"  [X] Failed to connect to TV at {tv_host}. Is Network Debugging enabled?")
        return False


def get_tv_existing_wallpapers(tv_host):
    """
    Fetch existing wallpapers on the TV.
    Returns:
      existing_hashes: set of lowercase md5 hashes
      existing_names: set of lowercase filenames
    """
    run_adb(["shell", "mkdir", "-p", TV_WALLPAPER_DIR], tv_host)
    
    # Run md5sum on TV
    res = run_adb(["shell", "md5sum", f"{TV_WALLPAPER_DIR}/*"], tv_host)
    existing_hashes = set()
    existing_names = set()
    
    for line in res.stdout.splitlines():
        line = line.strip()
        if not line or "No such file" in line or "is a directory" in line:
            continue
        parts = line.split(maxsplit=1)
        if len(parts) == 2:
            h, remote_path = parts[0].strip().lower(), parts[1].strip()
            fname = os.path.basename(remote_path).lower()
            existing_hashes.add(h)
            existing_names.add(fname)
            
    return existing_hashes, existing_names


def push_wallpaper_staged(local_path, clean_name, tv_host):
    """
    Push wallpaper using /data/local/tmp staging to bypass Android 14 sdcardfs restrictions.
    """
    tmp_path = f"{TV_TMP_DIR}/{clean_name}"
    final_path = f"{TV_WALLPAPER_DIR}/{clean_name}"

    # 1. Push to /data/local/tmp
    push_res = run_adb(["push", local_path, tmp_path], tv_host)
    if push_res.returncode != 0:
        return False, push_res.stderr.strip()

    # 2. Copy from tmp to CleanYourTV wallpapers directory
    cp_res = run_adb(["shell", f"cp {tmp_path} {final_path}"], tv_host)
    # 3. Clean up staging file
    run_adb(["shell", f"rm -f {tmp_path}"], tv_host)

    if cp_res.returncode != 0:
        return False, cp_res.stderr.strip()

    # 4. Set readable permissions (mode 666)
    run_adb(["shell", f"chmod 666 {final_path}"], tv_host)
    return True, None


def main():
    print("=" * 70)
    print("       CLEAN YOUR TV LAUNCHER - 4K WALLPAPER SYNC")
    print("       STRICT RULES: 4K UHD (min 3840x2160) | JPG/PNG only | No Duplicates")
    print("=" * 70)

    tv_host = get_tv_host()

    # Determine input folder
    folder = None
    for arg in sys.argv[1:]:
        if not arg.startswith("--") and os.path.isdir(arg):
            folder = arg
            break

    if not folder:
        print("\nNo folder provided. Opening folder picker...")
        folder = pick_folder_dialog()

    if not folder or not os.path.isdir(folder):
        print("\n[!] No valid folder selected. Exiting.")
        return

    folder = os.path.abspath(folder)
    print(f"\nTarget Folder: {folder}")

    # Connect to TV
    if not connect_tv(tv_host):
        wait_exit()
        sys.exit(1)

    print("\nReading existing wallpapers from TV...")
    tv_hashes, tv_names = get_tv_existing_wallpapers(tv_host)
    print(f"  Current wallpapers on TV: {len(tv_names)}")

    # Scan local folder
    print("\nScanning local images for 4K JPG/PNG files...")
    candidates = []
    skipped_format = 0
    skipped_resolution = 0
    skipped_size = 0

    for root, _, files in os.walk(folder):
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            full_path = os.path.join(root, f)

            # Strict format check: ONLY JPG and PNG (no WebP, GIF, etc.)
            if ext not in VALID_EXTENSIONS:
                skipped_format += 1
                if ext in {".webp", ".gif", ".bmp", ".tiff", ".avif", ".heic"}:
                    print(f"  [Skip: Not JPG/PNG] {f} ({ext} rejected - only JPG and PNG allowed)")
                continue

            # Check file size (<= 10 MB CleanYourTV limit)
            try:
                size = os.path.getsize(full_path)
                if size <= 0:
                    continue
                if size > MAX_SIZE_BYTES:
                    skipped_size += 1
                    print(f"  [Skip: Too Large]   {f} ({size / 1024 / 1024:.1f} MB exceeds 10 MB TV limit)")
                    continue
            except OSError:
                continue

            # Strict 4K resolution check (at least 3840x2160)
            width, height = get_image_dimensions(full_path)
            if not is_4k(width, height):
                skipped_resolution += 1
                res_str = f"{width}x{height}" if width and height else "unreadable"
                print(f"  [Skip: Not 4K]      {f} ({res_str} - requires at least 3840x2160)")
                continue

            # Passed all filters
            candidates.append((full_path, width, height))

    print("\n" + "-" * 70)
    print(f"  Qualified 4K images found: {len(candidates)}")
    if skipped_format:
        print(f"  Skipped (non-JPG/PNG)    : {skipped_format}")
    if skipped_resolution:
        print(f"  Skipped (under 4K)       : {skipped_resolution}")
    if skipped_size:
        print(f"  Skipped (over 10 MB)     : {skipped_size}")
    print("-" * 70)

    if not candidates:
        print("\nNo qualified 4K JPG/PNG wallpapers found in this folder.")
        wait_exit()
        return

    # Check duplicates and prepare uploads
    to_upload = []
    skipped_duplicate_hash = 0
    skipped_duplicate_name = 0

    print("\nChecking for duplicates on TV...")
    for path, w, h in candidates:
        local_md5 = compute_md5(path)
        if not local_md5:
            continue

        orig_name = os.path.basename(path)
        clean_name = sanitize_filename(orig_name)

        if local_md5 in tv_hashes:
            skipped_duplicate_hash += 1
            print(f"  [Duplicate Content] {orig_name} (identical image already on TV)")
            continue

        if clean_name.lower() in tv_names:
            skipped_duplicate_name += 1
            print(f"  [Duplicate Name]    {clean_name} (filename already exists on TV)")
            continue

        # Valid new 4K image
        to_upload.append((path, clean_name, local_md5, w, h))
        # Prevent duplicates within the same batch
        tv_hashes.add(local_md5)
        tv_names.add(clean_name.lower())

    print("\n" + "-" * 70)
    print(f"  4K Wallpapers ready to push : {len(to_upload)}")
    print(f"  Skipped (same content on TV): {skipped_duplicate_hash}")
    print(f"  Skipped (same name on TV)   : {skipped_duplicate_name}")
    print("-" * 70)

    if not to_upload:
        print("\nAll qualified 4K images are already on your TV! No changes made.")
        wait_exit()
        return

    # Upload files using staged push
    print("\nPushing 4K wallpapers to TV...")
    success_count = 0
    for i, (local_path, clean_name, _, w, h) in enumerate(to_upload, 1):
        print(f"  [{i}/{len(to_upload)}] Pushing {clean_name} ({w}x{h})...", end=" ", flush=True)
        ok, err = push_wallpaper_staged(local_path, clean_name, tv_host)
        if ok:
            print("OK")
            success_count += 1
        else:
            print(f"FAILED ({err})")

    # Reload CleanYourTV launcher
    print("\nReloading CleanYourTV launcher...")
    run_adb(["shell", "am force-stop com.gathin.tvhome; input keyevent 3"], tv_host)

    print("\n" + "=" * 70)
    print(f"SUCCESS! Added {success_count} new 4K wallpaper(s) to Clean Your TV.")
    print(f"Total wallpapers now on TV: {len(tv_names)}")
    print("=" * 70)
    wait_exit()


if __name__ == "__main__":
    main()
