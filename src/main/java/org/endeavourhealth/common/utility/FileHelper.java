package org.endeavourhealth.common.utility;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.google.common.base.Strings;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.endeavourhealth.common.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FileHelper {
    private static final Logger LOG = LoggerFactory.getLogger(FileHelper.class);

    private static final String STORAGE_PATH_PREFIX_S3_OLD_WAY = "S3";
    private static final String STORAGE_PATH_PREFIX_S3 = "s3://";
    private static final char UNIX_DELIM = '/';

    private static AmazonS3 cachedS3Client = null;

    public static String loadStringResource(String resourceLocation) throws Exception {
        URL url = Thread.currentThread().getContextClassLoader().getResource(resourceLocation);

        if (url == null)
            throw new RuntimeException(resourceLocation + " not found");

        return loadStringResource(url);
    }

    public static String loadStringResource(URL url) throws Exception {
        InputStream stream = url.openStream();
        String resource = IOUtils.toString(stream);
        IOUtils.closeQuietly(stream);

        return resource;
    }

    public static String loadStringFile(String location) throws IOException {
        return loadStringFile(Paths.get(location));
    }

    public static String loadStringFile(Path path) throws IOException {
        byte[] encoded = Files.readAllBytes(path);
        return new String(encoded, "UTF-8");
    }

    public static String combinePaths(String path1, String path2) {
        File file1 = new File(path1);
        File file2 = new File(file1, path2);
        return file2.getPath();
    }

    public static boolean createDirectory(String directory) {
        // S3 directories do not have to be pre-created
        if (directory.startsWith(STORAGE_PATH_PREFIX_S3)
                || directory.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {
            return true;
        }

        File file = new File(directory);

        if (!file.exists())
            return file.mkdir();

        return true;
    }

    public static String findFileRecursive(final File directoryStart, final String filename) {
        Stack<File> files = new Stack<>();
        files.addAll(Arrays.asList(directoryStart.listFiles()));

        while (!files.isEmpty()) {
            File file = files.pop();

            if (file.getName().toLowerCase().equals(filename.toLowerCase()))
                return file.getPath();

            if (file.isDirectory())
                files.addAll(Arrays.asList(file.listFiles()));
        }

        return null;
    }

    public static String findFileInJar(File jarFile, String filename) throws IOException {
        ZipFile zipFile = new ZipFile(jarFile);

        try {
            Enumeration zipEntries = zipFile.entries();

            while (zipEntries.hasMoreElements()) {
                ZipEntry zipEntry = (ZipEntry) zipEntries.nextElement();

                if (new File(zipEntry.getName()).getName().equals(filename))
                    return zipEntry.getName();
            }
        } finally {
            zipFile.close();
        }

        return null;
    }


    public static String readTextFile(Path file) throws Exception {

        try {
            if (!Files.exists(file))
                throw new Exception("Could not find file: " + file.getFileName());

            byte[] encoded = Files.readAllBytes(file);
            return new String(encoded, StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new Exception("Error reading file: " + file.getFileName(), e);
        }
    }

    public static boolean pathNotExists(String path) {
        Path pathObject = Paths.get(path);
        return Files.notExists(pathObject);
    }

    public static void deleteFromSharedStorage(String path) throws Exception {
        if (Strings.isNullOrEmpty(path)) {
            throw new IllegalArgumentException("Must provide path");
        }

        if (path.startsWith(STORAGE_PATH_PREFIX_S3)
                || path.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {

            //if we have an S3 bucket name, then we use the S3 api
            String s3BucketName = findS3BucketName(path);
            String keyName = findS3KeyName(path);

            AmazonS3 s3Client = getS3Client();

            DeleteObjectRequest deleteRequest = new DeleteObjectRequest(s3BucketName, keyName);
            s3Client.deleteObject(deleteRequest);

        } else {
            //if we don't have an S3 bucket name, then it's a normal file system
            File file = new File(path);
            if (file.exists()
                && !file.delete()) {
                throw new IOException("Failed to delete " + path);
            }
        }
    }

    public static void createFolder(Path folder) throws IOException {
        Files.createDirectory(folder);
    }

    public static void writeFileToSharedStorage(String destinationPath, File source) throws Exception {
        if (Strings.isNullOrEmpty(destinationPath)) {
            throw new IllegalArgumentException("Must provide storage path");
        }

        if (!source.exists()) {
            throw new IOException("Source file " + source + " doesn't exist");
        }

        if (destinationPath.startsWith(STORAGE_PATH_PREFIX_S3)
                || destinationPath.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {

            //if we have an S3 bucket name, then we use the S3 api
            String s3BucketName = findS3BucketName(destinationPath);
            String keyName = findS3KeyName(destinationPath);

            AmazonS3 s3Client = getS3Client();

            ObjectMetadata objectMetadata = new ObjectMetadata();

            String s = SSEAlgorithm.KMS.getAlgorithm();
            objectMetadata.setSSEAlgorithm(s);

            long bytes = source.length();
            long maxChunk = 1024L * 1024L * 1024L * 4L; //4GB
            //long maxChunk = 1024 * 1024 * 5; //5MB
            //LOG.trace("File size = " + bytes + " and multipart limit = " + maxChunk);
            if (bytes > maxChunk) {

                //S3 has a 5GB limit on put operations before you have to use the multipart API, but I'm going with 4GB just in case
                InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(s3BucketName, keyName);
                initRequest.setObjectMetadata(objectMetadata);

                InitiateMultipartUploadResult initResponse = s3Client.initiateMultipartUpload(initRequest);
                String uploadId = initResponse.getUploadId();

                try {
                    List<PartETag> partETags = new ArrayList<>();
                    long filePosition = 0;
                    int partNumber = 0;

                    while (filePosition < bytes) {

                        long bytesRemaining = bytes - filePosition;
                        //LOG.trace("File position = " + filePosition + " so bytes remaining are " + bytesRemaining);
                        long partSize = Math.min(maxChunk, bytesRemaining);
                        partNumber ++;
                        //LOG.trace("Writing part " + partNumber + " of " + partSize + " bytes (out of " + bytes + ") to " + keyName);

                        UploadPartRequest uploadRequest = new UploadPartRequest()
                                .withBucketName(s3BucketName)
                                .withKey(keyName)
                                .withUploadId(uploadId)
                                .withPartNumber(partNumber)
                                .withFileOffset(filePosition)
                                .withFile(source)
                                .withPartSize(partSize);

                        // Upload part and add response to our list.
                        UploadPartResult result = s3Client.uploadPart(uploadRequest);
                        PartETag tag = result.getPartETag();
                        partETags.add(tag);

                        filePosition += partSize;
                    }

                    //tell S3 we've completed the upload
                    CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(s3BucketName, keyName, uploadId, partETags);
                    s3Client.completeMultipartUpload(compRequest);

                } catch (Exception e) {
                    s3Client.abortMultipartUpload(new AbortMultipartUploadRequest(s3BucketName, keyName, uploadId));
                    throw e;
                }

            } else {
                //if smaller than our multipart limit, just upload in one go
                //LOG.info("Writing whole file of " + bytes + " bytes to " + keyName);
                PutObjectRequest putRequest = new PutObjectRequest(s3BucketName, keyName, source);
                putRequest.setMetadata(objectMetadata);

                s3Client.putObject(putRequest);
            }

        } else {
            //if we don't have an S3 bucket name, then it's a normal file system
            File destinationFile = new File(destinationPath);

            //just ensure we're not trying to write to the same place
            if (destinationFile.equals(source)) {
                throw new IOException("Destination is same as source: " + source);
            }

            if (destinationFile.exists()) {
                if (!destinationFile.delete()) {
                    throw new IOException("Failed to delete existing file " + destinationFile);
                }
            }

            File destinationDir = destinationFile.getParentFile();
            if (!destinationDir.exists()) {
                if (!destinationDir.mkdirs()) {
                    throw new IOException("Failed to create directory " + destinationDir);
                }
            }

            FileInputStream fis = new FileInputStream(source);
            try {
                Files.copy(fis, destinationFile.toPath());
            } finally {
                fis.close();
            }
        }
    }

    /**
     * writes a byte array to storage. Note this function doesn't support S3 multi-part uploads, since
     * we don't expect to have a 5GB array in memory
     */
    public static void writeBytesToSharedStorage(String destinationPath, byte[] bytes) throws Exception {
        if (Strings.isNullOrEmpty(destinationPath)) {
            throw new IllegalArgumentException("Must provide storage path");
        }

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);

        if (destinationPath.startsWith(STORAGE_PATH_PREFIX_S3)
                || destinationPath.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {

            //if we have an S3 bucket name, then we use the S3 api
            String s3BucketName = findS3BucketName(destinationPath);
            String keyName = findS3KeyName(destinationPath);

            AmazonS3 s3Client = getS3Client();

            ObjectMetadata objectMetadata = new ObjectMetadata();

            String s = SSEAlgorithm.KMS.getAlgorithm();
            objectMetadata.setSSEAlgorithm(s);
            objectMetadata.setContentLength(bytes.length);

            //if smaller than our multipart limit, just upload in one go
            //LOG.info("Writing whole file of " + bytes + " bytes to " + keyName);
            PutObjectRequest putRequest = new PutObjectRequest(s3BucketName, keyName, byteArrayInputStream, objectMetadata);

            s3Client.putObject(putRequest);

        } else {
            //if we don't have an S3 bucket name, then it's a normal file system
            File destinationFile = new File(destinationPath);

            if (destinationFile.exists()) {
                if (!destinationFile.delete()) {
                    throw new IOException("Failed to delete existing file " + destinationFile);
                }
            }

            File destinationDir = destinationFile.getParentFile();
            if (!destinationDir.exists()) {
                if (!destinationDir.mkdirs()) {
                    throw new IOException("Failed to create directory " + destinationDir);
                }
            }

            Files.copy(byteArrayInputStream, destinationFile.toPath());
        }
    }

    /**
     * fn to read the start of a file. The S3 API complains if you start reading a file but don't read to the end
     * so this fn allows us to just get the first few chars
     */
    public static String readFirstCharactersFromSharedStorage(String filePath, long numBytes) throws Exception {

        try {
            return readCharactersFromSharedStorage(filePath, 0, numBytes);

        } catch (AmazonS3Exception s3ex) {

            //if we try to request more data than exists in an S3 file, then it'll just
            //throw an exception. To give the same result as a file system file (i.e. read UP TO the number of bytes)
            //detect the error, then get the actual file size, and read that amount
            String msg = s3ex.getMessage();
            if (msg == null
                    || !msg.contains("The requested range is not satisfiable")) {
                throw s3ex;
            }

            FileInfo matchingInfo = null;
            List<FileInfo> infoList = listFilesInSharedStorageWithInfo(filePath);
            for (FileInfo info: infoList) {
                if (info.getFilePath().equalsIgnoreCase(filePath)) {
                    matchingInfo = info;
                    break;
                }
            }
            if (matchingInfo == null) {
                throw new Exception("Failed to find file length for " + filePath);
            }
            long len = matchingInfo.getSize();
            LOG.warn("Failed to read file from S3 due to request of " + numBytes + " being shorter than actual len " + len + " bytes, so will try again with correct length: " + filePath);
            return readCharactersFromSharedStorage(filePath, 0, len);
        }
    }

    public static String readCharactersFromSharedStorage(String filePath, long startOffset, long numBytes) throws Exception {

        InputStream inputStream = readFileFromSharedStorage(filePath, new Long(startOffset), new Long(numBytes));
        InputStreamReader reader = new InputStreamReader(inputStream, Charset.defaultCharset());

        try {
            StringBuilder sb = new StringBuilder();

            char[] buf = new char[1000];
            while (true) {
                int read = reader.read(buf);

                //the S3 API seems to sometimes return slightly more than requested, so apply
                //our limit in this check to make sure we only return what was specifically requested
                if (read == -1
                        || sb.length() >= numBytes) {
                    break;
                }

                sb.append(buf, 0, read);
            }

            return sb.toString();

        } finally {
            reader.close();
        }
    }

    public static InputStreamReader readFileReaderFromSharedStorage(String filePath) throws Exception {
        InputStream inputStream = readFileFromSharedStorage(filePath);
        return new InputStreamReader(inputStream, Charset.defaultCharset());
    }

    public static InputStreamReader readFileReaderFromSharedStorage(String filePath, Charset encoding) throws Exception {
        InputStream inputStream = readFileFromSharedStorage(filePath);
        return new InputStreamReader(inputStream, encoding);
    }

    public static InputStream readFileFromSharedStorage(String filePath) throws Exception {
        return readFileFromSharedStorage(filePath, null, null);
    }

    private static InputStream readFileFromSharedStorage(String filePath, Long startOffsetBytes, Long numBytesToRead) throws Exception {
        if (Strings.isNullOrEmpty(filePath)) {
            throw new IllegalArgumentException("Must provide storage path");
        }

        if (filePath.startsWith(STORAGE_PATH_PREFIX_S3)
                || filePath.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {

            //if we have an S3 bucket name, then we use the S3 api
            String s3BucketName = findS3BucketName(filePath);
            String keyName = findS3KeyName(filePath);

            GetObjectRequest request = new GetObjectRequest(s3BucketName, keyName);
            if (startOffsetBytes != null && numBytesToRead != null) {
                long startOffset = startOffsetBytes.longValue();
                long endOffset = startOffset + numBytesToRead.longValue() - 1; //end offset is inclusive, so need to subtract one to make exclusive
                request.setRange(startOffset, endOffset);
            }

            AmazonS3 s3Client = getS3Client();
            S3Object object = s3Client.getObject(request);

            InputStream inputStream = object.getObjectContent();
            return inputStream;

        } else {
            //if we don't have an S3 bucket name, then it's a normal file system
            File f = new File(filePath);

            if (startOffsetBytes != null && numBytesToRead != null) {
                //if wanting to read from an offset, we need to use a random access file to seek to the desired place
                return new FileHelper_RandomAccessInputStream(f, startOffsetBytes.longValue(), numBytesToRead.longValue());

            } else {
                //don't add buffering here, as it's already added in Apache CSVParser etc. and means we can't accurately count bytes
                return new FileInputStream(f);
                /*FileInputStream fis = new FileInputStream(f);
                BufferedInputStream bis = new BufferedInputStream(fis); //always makes sense to use a buffered reader
                return bis;*/
            }
        }
    }

    public static List<String> listFilesInSharedStorage(String dirPath) throws Exception {
        List<FileInfo> listing = listFilesInSharedStorageWithInfo(dirPath);
        return listing
                .stream()
                .map(T -> T.getFilePath())
                .collect(Collectors.toList());
    }

    public static List<FileInfo> listFilesInSharedStorageWithInfo(String dirPath) throws Exception {

        if (Strings.isNullOrEmpty(dirPath)) {
            throw new IllegalArgumentException("Must provide storage path");
        }

        List<FileInfo> ret = new ArrayList<>();

        if (dirPath.startsWith(STORAGE_PATH_PREFIX_S3)
                || dirPath.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {

            //if we have an S3 bucket name, then we use the S3 api
            String s3BucketName = findS3BucketName(dirPath);
            String keyPrefix = findS3KeyName(dirPath);

            ListObjectsV2Request request = new ListObjectsV2Request();
            request.setBucketName(s3BucketName);
            request.setPrefix(keyPrefix);

            AmazonS3 s3Client = getS3Client();

            while (true) {

                ListObjectsV2Result result = s3Client.listObjectsV2(request);
                if (result.getObjectSummaries() != null) {
                    for (S3ObjectSummary objectSummary : result.getObjectSummaries()) {
                        String key = objectSummary.getKey();

                        //we need to format the key so that it's in the format we expect
                        String matchedFile = null;
                        if (dirPath.startsWith(STORAGE_PATH_PREFIX_S3)) {
                            matchedFile = STORAGE_PATH_PREFIX_S3 + s3BucketName + UNIX_DELIM + key;

                        } else {
                            matchedFile = STORAGE_PATH_PREFIX_S3_OLD_WAY + UNIX_DELIM + s3BucketName + UNIX_DELIM + key;
                        }

                        //because of the way prefixes work (i.e. not like directories), if we ask for everything with prefix
                        //"test" we'll get back matches like test/a.txt but ALSO matches like testtest/a.txt. So we need to validate
                        //that we only return the first type of matches
                        if ((dirPath.endsWith("" + UNIX_DELIM) && matchedFile.startsWith(dirPath)) //if dirPath is "test/"
                            || (!dirPath.endsWith("" + UNIX_DELIM) && matchedFile.startsWith(dirPath + UNIX_DELIM)) //if dirPath is "test"
                            || (matchedFile.equals(dirPath))) { //if dirPath is an actual file "test/a.txt"

                            Date lastModified = objectSummary.getLastModified();
                            long size = objectSummary.getSize();

                            FileInfo info = new FileInfo(matchedFile, lastModified, size);
                            ret.add(info);
                        }
                    }
                }

                //the AWS function only returns 1000 results at a time, so check for this and request more if necessary
                if (result.isTruncated()) {
                    //the continuation token tells AWS where to resume
                    String continuationToken = result.getNextContinuationToken();
                    request.setContinuationToken(continuationToken);

                } else {
                    break;
                }
            }

        } else {
            //if we don't have an S3 bucket name, then it's a normal file system
            File f = new File(dirPath);
            listFilesInDirectoryRecursive(f, ret);
        }

        return ret;
    }

    private static void listFilesInDirectoryRecursive(File f, List<FileInfo> ret) {

        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File child: files) {
                    listFilesInDirectoryRecursive(child, ret);
                }
            }

        } else {
            String path = f.getAbsolutePath();
            Date lastModified = new Date(f.lastModified());
            long length = f.length();
            FileInfo info = new FileInfo(path, lastModified, length);
            ret.add(info);
        }

    }

    private static String findS3KeyName(String path) {
        path = normalisePath(path);

        if (path.startsWith(STORAGE_PATH_PREFIX_S3)) {
            //expected format is: s3://bucketname/key
            int prefixLen = STORAGE_PATH_PREFIX_S3.length();
            int endIndex = path.indexOf(UNIX_DELIM, prefixLen);
            if (endIndex == -1) {
                throw new IllegalArgumentException("Failed to find S3 key name from path " + path);
            }

            return path.substring(endIndex + 1);

        } else {
            //expected format is: S3/<bucketname>/key
            int firstSlash = path.indexOf(UNIX_DELIM);
            int secondSlash = path.indexOf(UNIX_DELIM, firstSlash + 1);

            if (firstSlash == -1
                    || secondSlash == -1) {
                throw new IllegalArgumentException("Failed to find S3 key name from path " + path);
            }

            return path.substring(secondSlash + 1);
        }
    }

    private static String findS3BucketName(String path) {
        path = normalisePath(path);

        if (path.startsWith(STORAGE_PATH_PREFIX_S3)) {
            //expected format is: s3://bucketname/key
            int prefixLen = STORAGE_PATH_PREFIX_S3.length();
            int endIndex = path.indexOf(UNIX_DELIM, prefixLen);
            if (endIndex == -1) {
                return path.substring(prefixLen);
            } else {
                return path.substring(prefixLen, endIndex);
            }

        } else {
            //old way expected format is: S3/<bucketname>/key
            int firstSlash = path.indexOf(UNIX_DELIM);
            int secondSlash = path.indexOf(UNIX_DELIM, firstSlash + 1);

            if (firstSlash == -1
                    || secondSlash == -1) {
                throw new IllegalArgumentException("Failed to find S3 bucket name from path " + path);
            }

            return path.substring(firstSlash + 1, secondSlash);
        }
    }

    private static String normalisePath(String path) {
        //the S3 key is a unix style path, so we need to make sure to convert any windows-style over
        if (path.indexOf("\\") > -1) {
            path = path.replace('\\', UNIX_DELIM);
        }
        return path;
    }

    private static AmazonS3 getS3Client() {
        if (cachedS3Client == null) {

            //ProfileCredentialsProvider credentialsProvider = new ProfileCredentialsProvider();

            AmazonS3ClientBuilder clientBuilder = AmazonS3ClientBuilder
                    .standard()
                    .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                    .withRegion(Regions.EU_WEST_2);

            cachedS3Client = clientBuilder.build();
        }

        return cachedS3Client;
    }

    /**
     * ensures all files are in the same directory (or S3 equivalent) and returns that parent directory
     */
    public static String validateFilesAreInSameDirectory(String[] files) throws FileNotFoundException {
        List<String> list = Arrays.asList(files);
        return validateFilesAreInSameDirectory(list);
    }

    public static String validateFilesAreInSameDirectory(List<String> files) throws FileNotFoundException {
        String ret = null;

        for (String file: files) {

            File f = new File(file); //this still works even for an S3 path
            String parent = f.getParent();

            if (ret == null) {
                ret = parent;

            } else {
                if (!ret.equalsIgnoreCase(parent)) {
                    String err = "" + f + " isn't in the expected directory structure within " + ret;
                    for (String s: files) {
                        err += "\n";
                        err += s;
                    }
                    throw new FileNotFoundException(err);
                }
            }
        }

        return ret;
    }

    /**
     * generic helper methods to cut down on duplicated code
     */
    public static void deleteRecursiveIfExists(String filePath) throws IOException {
        deleteRecursiveIfExists(new File(filePath));
    }

    public static void deleteRecursiveIfExists(File f) throws IOException {
        if (!f.exists()) {
            return;
        }

        if (f.isDirectory()) {
            for (File child: f.listFiles()) {
                deleteRecursiveIfExists(child);
            }
        }

        if (!f.delete()) {
            throw new IOException("Failed to delete " + f);
        }
    }

    public static boolean directoryExists(String dirPath) throws IOException {
        // S3 directories dont exist
        if (dirPath.startsWith(STORAGE_PATH_PREFIX_S3)
                || dirPath.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {
            return true;
        }

        File f = new File(dirPath);

        return f.exists() && f.isDirectory();
    }

    public static void createDirectoryIfNotExists(String dirPath) throws IOException {
        // S3 directories dont exist
        if (dirPath.startsWith(STORAGE_PATH_PREFIX_S3)
                || dirPath.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {
            return;
        }

        createDirectoryIfNotExists(new File(dirPath));
    }

    public static void createDirectoryIfNotExists(File f) throws IOException {
        if (f.exists()) {
            return;
        }

        if (f.mkdirs()) {
            return;
        }

        throw new IOException("Failed to create directory " + f.getAbsolutePath());
    }


    public static void setPermanentStorageTags(String path, Map<String, String> s3Tags) {
        if (s3Tags == null
                || s3Tags.isEmpty()) {
            return;
        }

        if (path.startsWith(STORAGE_PATH_PREFIX_S3)
                || path.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {

            AmazonS3 s3Client = getS3Client();

            String s3BucketName = findS3BucketName(path);
            String keyName = findS3KeyName(path);

            // Replace the object's tags with two new tags.
            List<Tag> newTags = new ArrayList<Tag>();
            for (String key : s3Tags.keySet()) {
                String value = s3Tags.get(key);
                newTags.add(new Tag(key, value));
            }

            s3Client.setObjectTagging(new SetObjectTaggingRequest(s3BucketName, keyName, new ObjectTagging(newTags)));

        } else {
            //don't support tags on normal file system
            throw new RuntimeException("Trying to set tags on non-S3 path " + path);
        }
    }

    public static Map<String, String> getPermanentStorageTags(String path) {

        if (path.startsWith(STORAGE_PATH_PREFIX_S3)
                || path.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {

            AmazonS3 s3Client = getS3Client();

            String s3BucketName = findS3BucketName(path);
            String keyName = findS3KeyName(path);

            GetObjectTaggingRequest taggingRequest = new GetObjectTaggingRequest(s3BucketName, keyName);
            GetObjectTaggingResult tagsResult = s3Client.getObjectTagging(taggingRequest);

            Map<String, String> ret = new HashMap<>();

            for (Tag tag: tagsResult.getTagSet()) {
                String key = tag.getKey();
                String value = tag.getValue();
                ret.put(key, value);
            }

            return ret;

        } else {
            //don't support tags on normal file system
            throw new RuntimeException("Trying to get tags on non-S3 path " + path);
        }
    }

    /**
     * returns a standard "temp" directory to use, under the user home directory, and factoring in the app ID
     */
    public static File getTempDir() throws Exception {
        String appId = ConfigManager.getAppId();

        String homeDir = System.getProperty("user.home");
        File tmpDir = new File(homeDir, appId + "_TMP");
        if (!tmpDir.exists()) {
            if (!tmpDir.mkdirs()) {
                throw new Exception("Failed to create temp directory " + tmpDir);
            }
        }

        return tmpDir;
    }

    /**
     * creates and returns a unique directory under the temp directory. Useful if multiple apps are doing
     * stuff with files of the same name, to avoid different instances overwriting files used by each other
     */
    public static File getTempDirRandomSubdirectory() throws Exception {
        File tempDir = getTempDir();
        File subTempDir = new File(tempDir, UUID.randomUUID().toString());
        if (!subTempDir.exists()) {
            boolean createDir = subTempDir.mkdirs();
            if (!createDir) {
                throw new Exception("Failed to create temp dir " + subTempDir);
            }
        }

        return subTempDir;
    }

    /**
     * if the given file path is an S3 path, it'll copy the file to a local file in a temp directory
     * if the file path is NOT an S3 path, just return a File object for that path
     */
    public static File copyFileFromStorageToTempDirIfNecessary(String filePath) throws Exception {

        //check if an S3 path or not
        if (!filePath.startsWith(STORAGE_PATH_PREFIX_S3)
                && !filePath.startsWith(STORAGE_PATH_PREFIX_S3_OLD_WAY)) {
            return new File(filePath);
        }

        //give the temp file a unique name, so no chance of mixing up with anything else
        File dir = new File(getTempDir(), UUID.randomUUID().toString());
        dir.mkdirs();

        String fileName = FilenameUtils.getName(filePath);
        File dst = new File(dir, fileName);

        //copy from S3 to tmp
        InputStream is = readFileFromSharedStorage(filePath);
        try {
            Files.copy(is, dst.toPath());

        } finally {
            is.close();
        }

        return dst;
    }

    /**
     * if the file object was created using the above copy function then this will
     * delete the file, otherwise does nothing
     */
    public static void deleteFileFromTempDirIfNecessary(File f) throws Exception {

        File uuidDir = f.getParentFile();
        if (uuidDir == null) {
            return;
        }

        File possibleTempDir = uuidDir.getParentFile();
        if (possibleTempDir == null) {
            return;
        }

        File tempDir = getTempDir();
        if (!possibleTempDir.equals(tempDir)) {
            return;
        }

        //delete the UUID directory and down
        deleteRecursiveIfExists(uuidDir);
    }

    /**
     * varargs fn to concatenate multiple tokens into a file path
     */
    public static String concatFilePath(String... toks) {
        if (toks.length == 0) {
            throw new IllegalArgumentException("Passed empty arguments");
        }

        if (toks.length == 1) {
            return toks[0];
        }

        String s = toks[0];
        for (int i=1; i<toks.length; i++) {
            s = FilenameUtils.concat(s, toks[i]);
        }
        return s;
    }
}

/**
 * input stream that uses a RandomAccessFile to read from a file, to get around
 * RandomAccessFile not being a sub-class of InputStream
 */
class FileHelper_RandomAccessInputStream extends InputStream {

    private final RandomAccessFile raf;
    private final long bytesToRead;
    private long bytesRead = 0;

    public FileHelper_RandomAccessInputStream(File src, long offset, long bytesToRead) throws IOException {
        this.raf = new RandomAccessFile(src, "r");
        this.bytesToRead = bytesToRead;

        //seek to the starting offset
        this.raf.seek(offset);
    }

    @Override
    public int read() throws IOException {

        //if we've already read to our limit, return -1 to say we're finished
        if (bytesRead >= bytesToRead) {
            return -1;
        }

        bytesRead ++;
        return raf.read();
    }

    @Override
    public int read(byte b[], int off, int len) throws IOException {

        //if we've already read to our limit, return -1 to say we're finished
        if (bytesRead >= bytesToRead) {
            return -1;
        }

        //if we're asking to read beyond what we originally specified in the constructor, then restrict to that limit
        if (bytesRead + len > bytesToRead) {
            len = (int)(bytesToRead - bytesRead);
        }

        bytesRead += len;
        return raf.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        this.raf.close();
    }

}
