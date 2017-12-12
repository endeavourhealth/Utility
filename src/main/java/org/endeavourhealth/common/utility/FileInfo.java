package org.endeavourhealth.common.utility;

import java.util.Date;

public class FileInfo {

    private String filePath;
    private Date lastModified;
    private long size;

    public FileInfo(String filePath, Date lastModified, long size) {
        this.filePath = filePath;
        this.lastModified = lastModified;
        this.size = size;
    }

    public String getFilePath() {
        return filePath;
    }

    public Date getLastModified() {
        return lastModified;
    }

    public long getSize() {
        return size;
    }
}
