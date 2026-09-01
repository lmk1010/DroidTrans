package com.mk.androidtransfer.database;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class UploadRecordFilesTest {

    @Test
    public void returnsOnlySuccessfulStablePaths() {
        Set<String> paths = UploadRecordFiles.successfulPaths(
                "[{\"path\":\"/a.jpg\",\"success\":true},"
                        + "{\"path\":\"/b.jpg\",\"success\":false},"
                        + "{\"path\":\"/a.jpg\",\"success\":true}]"
        );

        assertEquals(1, paths.size());
        assertTrue(paths.contains("/a.jpg"));
    }

    @Test
    public void treatsMissingSuccessAsSuccessfulForLegacyRecords() {
        Set<String> paths = UploadRecordFiles.successfulPaths(
                "[{\"path\":\"content://media/1\",\"name\":\"photo.jpg\"}]"
        );

        assertEquals(1, paths.size());
        assertTrue(paths.contains("content://media/1"));
    }

    @Test
    public void ignoresMalformedRecords() {
        assertTrue(UploadRecordFiles.successfulPaths("not-json").isEmpty());
        assertTrue(UploadRecordFiles.successfulPaths("[{\"success\":true}]").isEmpty());
    }
}
