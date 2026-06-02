package com.voidterm.contracts;

import java.io.File;

/** Plain-data description of one file to download (DTO crossing the download boundary). */
public final class FileSpec {
    public final String url;
    public final File destFile;
    public final String label;

    public FileSpec(String url, File destFile, String label) {
        this.url = url;
        this.destFile = destFile;
        this.label = label;
    }
}
