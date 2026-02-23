package com.voidterm.app;

import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and installs the Termux bootstrap environment (aarch64) from GitHub Releases.
 *
 * The bootstrap provides a full Linux userland (bash, apt, coreutils, etc.) under
 * the app's private files directory. Since Termux binaries are compiled with
 * /data/data/com.termux/files/usr hardcoded, we use LD_PRELOAD=libtermux-exec.so
 * to remap execve() paths at runtime.
 */
public class TermuxBootstrapInstaller {

    private static final String TAG = "TermuxBootstrap";
    private static final String RELEASES_API =
            "https://api.github.com/repos/termux/termux-packages/releases/latest";
    private static final String BOOTSTRAP_ASSET_NAME = "bootstrap-aarch64.zip";

    private final File filesDir;
    private final File prefixDir;
    private final File homeDir;
    private final File stagingDir;
    private final Handler mainHandler;

    public interface BootstrapCallback {
        void onProgressUpdate(String message, int percent);
        void onInstallComplete();
        void onInstallFailed(String error);
    }

    public TermuxBootstrapInstaller(File filesDir) {
        this.filesDir = filesDir;
        this.prefixDir = new File(filesDir, "usr");
        this.homeDir = new File(filesDir, "home");
        this.stagingDir = new File(filesDir, "usr-staging");
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public boolean isInstalled() {
        return new File(prefixDir, "bin/bash").exists();
    }

    public void install(BootstrapCallback callback) {
        // Clean up any failed previous install
        if (stagingDir.exists()) {
            deleteRecursive(stagingDir);
        }

        new Thread(() -> {
            try {
                doInstall(callback);
            } catch (Exception e) {
                Log.e(TAG, "Bootstrap install failed", e);
                postFailed(callback, e.getMessage());
            }
        }, "bootstrap-installer").start();
    }

    private void doInstall(BootstrapCallback callback) throws Exception {
        // Step 1: Resolve bootstrap URL from GitHub API
        postProgress(callback, "Finding latest bootstrap...", 0);
        String downloadUrl = resolveBootstrapUrl();
        Log.i(TAG, "Bootstrap URL: " + downloadUrl);

        // Step 2: Download the ZIP
        postProgress(callback, "Downloading bootstrap...", 5);
        byte[] zipData = downloadWithProgress(downloadUrl, callback);
        Log.i(TAG, "Downloaded " + zipData.length + " bytes");

        // Step 3: Extract to staging
        postProgress(callback, "Extracting files...", 70);
        stagingDir.mkdirs();
        extractZip(zipData, stagingDir, callback);

        // Step 4: Fix permissions on all directories (mkdirs() doesn't inherit chmod)
        postProgress(callback, "Setting permissions...", 85);
        chmodRecursive(stagingDir);

        // Step 5: Patch hardcoded /data/data/com.termux/ paths to our package.
        // Must run BEFORE symlinks so SYMLINKS.txt targets get patched too.
        postProgress(callback, "Patching paths...", 88);
        patchTermuxPaths(stagingDir);

        // Step 6: Process SYMLINKS.txt (now with patched targets)
        postProgress(callback, "Creating symlinks...", 92);
        processSymlinks(stagingDir);

        // Step 7: Create home directory with shell init files
        homeDir.mkdirs();
        Os.chmod(homeDir.getAbsolutePath(), 0700);
        createShellInitFiles(homeDir);

        // Step 8: Create tmp directory
        File tmpDir = new File(stagingDir, "tmp");
        tmpDir.mkdirs();
        Os.chmod(tmpDir.getAbsolutePath(), 0700);

        // Step 9: Configure apt and dpkg for our prefix
        postProgress(callback, "Configuring package manager...", 95);
        configureApt(stagingDir);
        configureDpkg(stagingDir);

        // Step 10: Atomic rename staging -> prefix
        if (prefixDir.exists()) {
            deleteRecursive(prefixDir);
        }
        if (!stagingDir.renameTo(prefixDir)) {
            throw new IOException("Failed to rename staging to prefix");
        }

        // Step 11: Create symlinks for binary-patched ELF paths.
        // ELF binaries had "com.termux/files" → "com.voidterm/fil" and
        // "com.termux/cache" → "com.voidterm/cac" (same-length patches).
        createFilSymlink();
        createCacSymlink();
        createAptCacheDirs();

        // Step 12: Write patch version marker
        writePatchVersion();

        Log.i(TAG, "Bootstrap installed successfully to " + prefixDir.getAbsolutePath());
        postProgress(callback, "Done!", 100);
        mainHandler.post(callback::onInstallComplete);
    }

    private String resolveBootstrapUrl() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("GitHub API returned " + code);
            }

            String json = readStream(conn.getInputStream());
            JSONObject release = new JSONObject(json);
            JSONArray assets = release.getJSONArray("assets");

            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (BOOTSTRAP_ASSET_NAME.equals(asset.getString("name"))) {
                    return asset.getString("browser_download_url");
                }
            }
            throw new IOException("Asset " + BOOTSTRAP_ASSET_NAME + " not found in latest release");
        } finally {
            conn.disconnect();
        }
    }

    private byte[] downloadWithProgress(String urlStr, BootstrapCallback callback) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);

        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Download failed with HTTP " + code);
            }

            int totalBytes = conn.getContentLength();
            InputStream in = new BufferedInputStream(conn.getInputStream(), 8192);
            ByteArrayOutputStream out = new ByteArrayOutputStream(totalBytes > 0 ? totalBytes : 32 * 1024 * 1024);

            byte[] buffer = new byte[8192];
            int bytesRead;
            int downloaded = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                if (totalBytes > 0) {
                    int percent = 5 + (int) ((downloaded / (float) totalBytes) * 65); // 5% to 70%
                    String msg = String.format("Downloading... (%d/%d MB)",
                            downloaded / (1024 * 1024), totalBytes / (1024 * 1024));
                    postProgress(callback, msg, percent);
                }
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private void extractZip(byte[] zipData, File destDir, BootstrapCallback callback) throws Exception {
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(zipData);
        ZipInputStream zis = new ZipInputStream(bais);
        ZipEntry entry;
        byte[] buffer = new byte[8192];
        int entryCount = 0;

        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            File outFile = new File(destDir, name);

            // Security: prevent zip path traversal
            if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                throw new SecurityException("Zip entry outside target dir: " + name);
            }

            if (entry.isDirectory()) {
                outFile.mkdirs();
                Os.chmod(outFile.getAbsolutePath(), 0700);
            } else {
                outFile.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(outFile);
                int len;
                while ((len = zis.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.close();

                // Set proper POSIX permissions via Os.chmod (File.setExecutable is unreliable on Android)
                Os.chmod(outFile.getAbsolutePath(), 0700);
            }

            entryCount++;
            if (entryCount % 100 == 0) {
                postProgress(callback, "Extracting files... (" + entryCount + ")", 75);
            }

            zis.closeEntry();
        }
        zis.close();
        Log.i(TAG, "Extracted " + entryCount + " entries");
    }

    private void processSymlinks(File baseDir) throws Exception {
        File symlinksFile = new File(baseDir, "SYMLINKS.txt");
        if (!symlinksFile.exists()) {
            Log.w(TAG, "SYMLINKS.txt not found, skipping");
            return;
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(symlinksFile)));
        String line;
        int count = 0;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Format: "target←linkpath" (← is a 4-byte UTF-8 sequence)
            String[] parts = line.split("\u2190");
            if (parts.length != 2) {
                Log.w(TAG, "Invalid symlink line: " + line);
                continue;
            }

            String target = parts[0].trim();
            String linkPath = parts[1].trim();
            File linkFile = new File(baseDir, linkPath);

            // Ensure parent directory exists
            linkFile.getParentFile().mkdirs();

            // Remove existing file if any
            if (linkFile.exists()) {
                linkFile.delete();
            }

            try {
                Os.symlink(target, linkFile.getAbsolutePath());
                count++;
            } catch (Exception e) {
                Log.w(TAG, "Failed to create symlink: " + linkPath + " -> " + target, e);
            }
        }
        reader.close();
        symlinksFile.delete();
        Log.i(TAG, "Created " + count + " symlinks");
    }

    private void configureApt(File usrDir) throws IOException {
        File aptConfDir = new File(usrDir, "etc/apt/apt.conf.d");
        aptConfDir.mkdirs();

        // Remove stale config from previous installs that set Dir (causes double-prefixed paths)
        File staleConf = new File(aptConfDir, "99-voidterm.conf");
        if (staleConf.exists()) {
            staleConf.delete();
        }
        Log.i(TAG, "apt config dir ready: " + aptConfDir.getAbsolutePath());
    }

    /**
     * Configure dpkg's instdir so that .deb packages (which contain hardcoded
     * com.termux paths) extract to our com.voidterm filesystem via a symlink.
     *
     * Layout:
     *   dpkg-root/data/data/com.termux → /data/data/com.voidterm (symlink)
     *
     * So dpkg extracting ./data/data/com.termux/files/usr/bin/ssh ends up at:
     *   dpkg-root/data/data/com.termux/files/usr/bin/ssh
     *   → /data/data/com.voidterm/files/usr/bin/ssh
     */
    private void configureDpkg(File usrDir) throws Exception {
        File dpkgRoot = new File(filesDir, "dpkg-root");
        // admindir must be INSIDE instdir for dpkg to accept it.
        // Use the com.termux symlink path so it's technically under dpkg-root
        // but resolves to the real admindir via the symlink.
        String adminPath = dpkgRoot.getAbsolutePath() + "/data/data/com.termux/files/usr/var/lib/dpkg";

        // dpkg.cfg.d — for direct dpkg invocations
        File dpkgConfD = new File(usrDir, "etc/dpkg/dpkg.cfg.d");
        dpkgConfD.mkdirs();
        File dpkgConf = new File(dpkgConfD, "voidterm.cfg");
        String dpkgConfig = "instdir " + dpkgRoot.getAbsolutePath() + "\n" +
                            "admindir " + adminPath + "\n";
        FileOutputStream fos = new FileOutputStream(dpkgConf);
        fos.write(dpkgConfig.getBytes());
        fos.close();

        // apt.conf.d — apt calls dpkg with --instdir=/ which overrides dpkg.cfg,
        // so we must pass our instdir via DPkg::Options to override apt's default.
        File aptConfD = new File(usrDir, "etc/apt/apt.conf.d");
        aptConfD.mkdirs();
        File aptDpkgConf = new File(aptConfD, "99-voidterm-dpkg.conf");
        String aptConfig =
                "DPkg::Options:: \"--instdir=" + dpkgRoot.getAbsolutePath() + "\";\n" +
                "DPkg::Options:: \"--admindir=" + adminPath + "\";\n";
        fos = new FileOutputStream(aptDpkgConf);
        fos.write(aptConfig.getBytes());
        fos.close();

        // Create dpkg-root/data/data/ directory structure
        File dataDataDir = new File(dpkgRoot, "data/data");
        dataDataDir.mkdirs();

        // Symlink: dpkg-root/data/data/com.termux → our app's data dir
        File termuxCompat = new File(dataDataDir, "com.termux");
        if (!termuxCompat.exists()) {
            Os.symlink(filesDir.getParentFile().getAbsolutePath(), termuxCompat.getAbsolutePath());
        }

        // Post-invoke hook: automatically patch newly installed packages.
        // ELF: perl for binary-safe same-length replacement (skip if no perl).
        // Text: grep -I (auto-skips binary) + sed. LD_PRELOAD catches misses.
        File patchScript = new File(usrDir, "lib/voidterm-patch-new.sh");
        String scriptContent =
                "#!/data/data/com.voidterm/files/usr/bin/sh\n" +
                "# Auto-patch newly installed packages: com.termux -> com.voidterm\n" +
                "# Called by apt via DPkg::Post-Invoke after each install/upgrade.\n" +
                "INFODIR=\"/data/data/com.voidterm/files/usr/var/lib/dpkg/info\"\n" +
                "\n" +
                "patch_file() {\n" +
                "    f=\"$1\"\n" +
                "    [ -f \"$f\" ] || return 0\n" +
                "    [ -s \"$f\" ] || return 0\n" +
                "    # Read first 4 bytes to detect ELF magic (7f 45 4c 46)\n" +
                "    magic=$(dd if=\"$f\" bs=1 count=4 2>/dev/null | od -An -tx1 | tr -d ' ')\n" +
                "    case \"$magic\" in\n" +
                "    7f454c46)\n" +
                "        # ELF binary: same-length replacement (16 chars each)\n" +
                "        # perl -pi handles binary data safely; skip if unavailable\n" +
                "        command -v perl >/dev/null 2>&1 || return 0\n" +
                "        perl -pi -e 's|com\\.termux/files|com.voidterm/fil|g;" +
                "s|com\\.termux/cache|com.voidterm/cac|g' \"$f\" 2>/dev/null\n" +
                "        ;;\n" +
                "    *)\n" +
                "        # grep -I auto-skips binary files (archives, images, .pyc, etc.)\n" +
                "        grep -Iql 'com\\.termux' \"$f\" 2>/dev/null && \\\n" +
                "            sed -i 's|com\\.termux|com.voidterm|g' \"$f\" 2>/dev/null\n" +
                "        ;;\n" +
                "    esac\n" +
                "}\n" +
                "\n" +
                "# Process .list files modified in the last 2 minutes (just-installed packages)\n" +
                "for listfile in \"$INFODIR\"/*.list; do\n" +
                "    [ -f \"$listfile\" ] || continue\n" +
                "    find \"$listfile\" -mmin -2 2>/dev/null | grep -q . || continue\n" +
                "    while IFS= read -r filepath; do\n" +
                "        patch_file \"$filepath\"\n" +
                "    done < \"$listfile\"\n" +
                "done\n";
        fos = new FileOutputStream(patchScript);
        fos.write(scriptContent.getBytes());
        fos.close();
        patchScript.setExecutable(true, false);

        // Always use the FINAL prefix path (not staging) so apt finds the script
        // after staging dir is renamed to prefix during fresh install.
        String scriptFinalPath = new File(prefixDir, "lib/voidterm-patch-new.sh").getAbsolutePath();
        File aptPatchConf = new File(aptConfD, "99-voidterm-patcher.conf");
        String patchConfig = "DPkg::Post-Invoke { \"sh " + scriptFinalPath +
                " 2>/dev/null || true\"; };\n";
        fos = new FileOutputStream(aptPatchConf);
        fos.write(patchConfig.getBytes());
        fos.close();

        Log.i(TAG, "dpkg configured with instdir: " + dpkgRoot.getAbsolutePath());
    }

    private String readStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private void createShellInitFiles(File home) throws IOException {
        String prefix = prefixDir.getAbsolutePath();

        // ~/.profile — sourced by bash --login after the (failing) compiled-in /etc/profile
        File profile = new File(home, ".profile");
        if (!profile.exists()) {
            String content =
                    "# Source the real profile from our prefix\n" +
                    "if [ -f \"$PREFIX/etc/profile\" ]; then\n" +
                    "    . \"$PREFIX/etc/profile\"\n" +
                    "fi\n";
            writeTextFile(profile, content);
        }

        // ~/.bashrc — our main init file (bash is started with --rcfile ~/.bashrc)
        // Sources both profile (env setup) and bashrc (interactive config)
        File bashrc = new File(home, ".bashrc");
        if (!bashrc.exists()) {
            String content =
                    "# Guard: Termux profile may exec bash, which re-reads .bashrc.\n" +
                    "# Prevent infinite loop by skipping profile if already sourced.\n" +
                    "if [ -z \"$VOIDTERM_PROFILE_SOURCED\" ]; then\n" +
                    "    export VOIDTERM_PROFILE_SOURCED=1\n" +
                    "    if [ -f \"$PREFIX/etc/profile\" ]; then\n" +
                    "        . \"$PREFIX/etc/profile\"\n" +
                    "    fi\n" +
                    "fi\n" +
                    "# Source interactive bash config\n" +
                    "if [ -f \"$PREFIX/etc/bash.bashrc\" ]; then\n" +
                    "    . \"$PREFIX/etc/bash.bashrc\"\n" +
                    "fi\n" +
                    "\n" +
                    "# Ensure VoidTerm path remap stays active after Termux profile overwrites LD_PRELOAD\n" +
                    "case \"$LD_PRELOAD\" in\n" +
                    "    *libvoidterm-remap.so*) ;;\n" +
                    "    *) export LD_PRELOAD=\"${LD_PRELOAD:+$LD_PRELOAD:}$PREFIX/lib/libvoidterm-remap.so\" ;;\n" +
                    "esac\n";
            writeTextFile(bashrc, content);
        }
    }

    private void writeTextFile(File file, String content) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes());
        fos.close();
    }

    private static final int PATCH_VERSION = 11;

    private static final String TERMUX_OLD_PKG = "com.termux";
    private static final String VOIDTERM_PKG = "com.voidterm";

    // Binary patch: same-length replacement for ELF .rodata strings.
    // "com.termux/files" (16 chars) → "com.voidterm/fil" (16 chars)
    // A symlink /data/data/com.voidterm/fil → files makes paths resolve.
    private static final byte[] ELF_SEARCH = "com.termux/files".getBytes();
    private static final byte[] ELF_REPLACE = "com.voidterm/fil".getBytes();

    // "com.termux/cache" (16 chars) → "com.voidterm/cac" (16 chars)
    // A symlink /data/data/com.voidterm/cac → cache makes paths resolve.
    private static final byte[] ELF_CACHE_SEARCH = "com.termux/cache".getBytes();
    private static final byte[] ELF_CACHE_REPLACE = "com.voidterm/cac".getBytes();

    private void patchTermuxPaths(File dir) {
        patchFilesRecursive(dir);
    }

    /**
     * Fix symlinks whose targets contain the old com.termux package name.
     * SYMLINKS.txt in the bootstrap uses absolute targets like
     * /data/data/com.termux/files/usr/... which become broken after repackaging.
     */
    private void patchSymlinkTargets(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            try {
                StructStat stat = Os.lstat(child.getAbsolutePath());
                if (OsConstants.S_ISLNK(stat.st_mode)) {
                    String target = Os.readlink(child.getAbsolutePath());
                    if (target.contains(TERMUX_OLD_PKG)) {
                        String newTarget = target.replace(TERMUX_OLD_PKG, VOIDTERM_PKG);
                        child.delete();
                        Os.symlink(newTarget, child.getAbsolutePath());
                        Log.i(TAG, "Fixed symlink: " + child.getName() + " -> " + newTarget);
                    }
                } else if (OsConstants.S_ISDIR(stat.st_mode)) {
                    patchSymlinkTargets(child);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to check/fix symlink: " + child, e);
            }
        }
    }

    private void patchFilesRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    patchFilesRecursive(child);
                }
            }
            return;
        }

        if (file.length() > 10 * 1024 * 1024) return; // skip files > 10MB
        if (file.length() < 4) return;

        try {
            byte[] content = readFileBytes(file);

            // Detect ELF binary (magic: 7f 45 4c 46) → same-length binary patch
            if (content[0] == 0x7f && content[1] == 'E' && content[2] == 'L' && content[3] == 'F') {
                boolean modified = binaryReplace(content, ELF_SEARCH, ELF_REPLACE);
                modified |= binaryReplace(content, ELF_CACHE_SEARCH, ELF_CACHE_REPLACE);
                if (modified) {
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(content);
                    fos.close();
                    Log.i(TAG, "Binary-patched ELF: " + file.getName());
                }
                return;
            }

            // Skip non-text binary files (archives, compiled data, etc.)
            // Text replace changes length (10→11 chars) which corrupts binary formats.
            if (!isProbablyText(content)) return;

            String text = new String(content);
            if (text.contains(TERMUX_OLD_PKG)) {
                String patched = text.replace(TERMUX_OLD_PKG, VOIDTERM_PKG);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(patched.getBytes());
                fos.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to patch: " + file, e);
        }
    }

    /**
     * Heuristic: a file is probably text if it has no NUL bytes in its first 8KB.
     * Same approach as git's binary detection.
     */
    private boolean isProbablyText(byte[] content) {
        int checkLen = Math.min(content.length, 8192);
        for (int i = 0; i < checkLen; i++) {
            if (content[i] == 0) return false;
        }
        return true;
    }

    private boolean binaryReplace(byte[] data, byte[] search, byte[] replace) {
        boolean modified = false;
        for (int i = 0; i <= data.length - search.length; i++) {
            boolean match = true;
            for (int j = 0; j < search.length; j++) {
                if (data[i + j] != search[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(replace, 0, data, i, replace.length);
                i += replace.length - 1;
                modified = true;
            }
        }
        return modified;
    }

    private byte[] readFileBytes(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        int offset = 0;
        while (offset < data.length) {
            int read = fis.read(data, offset, data.length - offset);
            if (read < 0) break;
            offset += read;
        }
        fis.close();
        return data;
    }

    private void chmodRecursive(File dir) {
        try {
            Os.chmod(dir.getAbsolutePath(), 0700);
        } catch (Exception e) {
            Log.w(TAG, "chmod failed: " + dir, e);
        }
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory()) {
                        chmodRecursive(child);
                    } else {
                        try {
                            Os.chmod(child.getAbsolutePath(), 0700);
                        } catch (Exception e) {
                            Log.w(TAG, "chmod failed: " + child, e);
                        }
                    }
                }
            }
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private void postProgress(BootstrapCallback callback, String message, int percent) {
        mainHandler.post(() -> callback.onProgressUpdate(message, percent));
    }

    private void postFailed(BootstrapCallback callback, String error) {
        mainHandler.post(() -> callback.onInstallFailed(error));
    }

    /**
     * Returns true if bootstrap exists but was patched with an older version.
     */
    public boolean needsRepatch() {
        if (!isInstalled()) return false;
        File versionFile = new File(prefixDir, ".patch_version");
        if (!versionFile.exists()) return true;
        try {
            String content = new String(readFileBytes(versionFile)).trim();
            return Integer.parseInt(content) < PATCH_VERSION;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Re-patch an existing installation (no re-download).
     */
    public void repatch(BootstrapCallback callback) {
        new Thread(() -> {
            try {
                postProgress(callback, "Re-patching paths...", 10);
                patchTermuxPaths(prefixDir);

                postProgress(callback, "Fixing symlink targets...", 50);
                patchSymlinkTargets(prefixDir);

                postProgress(callback, "Fixing apt config...", 70);
                configureApt(prefixDir);
                configureDpkg(prefixDir);

                postProgress(callback, "Creating symlinks...", 80);
                createFilSymlink();
                createCacSymlink();
                createAptCacheDirs();
                writePatchVersion();

                postProgress(callback, "Done!", 100);
                Log.i(TAG, "Re-patch completed (version " + PATCH_VERSION + ")");
                mainHandler.post(callback::onInstallComplete);
            } catch (Exception e) {
                Log.e(TAG, "Re-patch failed", e);
                postFailed(callback, e.getMessage());
            }
        }, "bootstrap-repatcher").start();
    }

    private void createFilSymlink() throws Exception {
        File filSymlink = new File(filesDir.getParentFile(), "fil");
        if (!filSymlink.exists()) {
            Os.symlink("files", filSymlink.getAbsolutePath());
            Log.i(TAG, "Created symlink: fil -> files");
        }
    }

    private void createCacSymlink() throws Exception {
        File cacSymlink = new File(filesDir.getParentFile(), "cac");
        if (!cacSymlink.exists()) {
            Os.symlink("cache", cacSymlink.getAbsolutePath());
            Log.i(TAG, "Created symlink: cac -> cache");
        }
    }

    private void createAptCacheDirs() {
        File archivesDir = new File(filesDir.getParentFile(), "cache/apt/archives/partial");
        if (!archivesDir.exists()) {
            archivesDir.mkdirs();
            Log.i(TAG, "Created apt cache dirs: " + archivesDir.getAbsolutePath());
        }
    }

    private void writePatchVersion() throws IOException {
        File versionFile = new File(prefixDir, ".patch_version");
        FileOutputStream fos = new FileOutputStream(versionFile);
        fos.write(String.valueOf(PATCH_VERSION).getBytes());
        fos.close();
    }

    public File getPrefixDir() {
        return prefixDir;
    }

    public File getHomeDir() {
        return homeDir;
    }
}
