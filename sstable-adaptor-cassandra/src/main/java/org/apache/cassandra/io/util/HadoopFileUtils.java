package org.apache.cassandra.io.util;

import io.netty.util.concurrent.FastThreadLocal;
import org.apache.cassandra.config.Config;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.List;


public class HadoopFileUtils {

    //public static Configuration CONF;
    public static int DEFAULT_BUFFER_SIZE = 65536;

    private static final Logger LOGGER = LoggerFactory.getLogger(HadoopFileUtils.class);

    static {
        //CONF = new Configuration(); //load stuffs from properties files?
    }

    public static boolean exists(String filePath, Configuration conf) {
        filePath = normalizeFileName(filePath);

        try {
            Path path = new Path(filePath);
            FileSystem fs = path.getFileSystem(conf);
            return fs.exists(path);
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
            throw new RuntimeException(e.getCause());
        }
    }

    /**
     * Delete a file or a directory (recursively)
     * @param filePath file or directory path
     * @param conf hadoop configuration
     * @return
     */
    public static boolean delete(String filePath, Configuration conf) {
        filePath = normalizeFileName(filePath);
        try {
            Path path = new Path(filePath);
            FileSystem fs = path.getFileSystem(conf);
            fs.delete(path, true);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Unable to delete file " + filePath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Delete if the path exists
     * @param filePath
     * @param conf
     * @return
     */
    public static boolean deleteIfExists(String filePath, Configuration conf) {
        filePath = normalizeFileName(filePath);

        try {
            Path path = new Path(filePath);
            FileSystem fs = path.getFileSystem(conf);
            if (fs.exists(path)) {
                fs.delete(path, true);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("Unable to check the existence and to delete for file " + filePath + ": " + e.getMessage());
            return false;
        }
    }

    public static String normalizeFileName(String fileName)
    {
        if (fileName.startsWith("s3n:")) {
            return fileName.replace("s3n:", "s3:");
        }

        if (fileName.startsWith("s3a:")) {
            return fileName.replace("s3a:", "s3:");
        }

        return fileName;
    }

    public static BufferedWriter newBufferedWriter(String filePath, Charset cs, Configuration conf)
        throws IOException
    {
        CharsetEncoder encoder = cs.newEncoder();
        Writer writer = new OutputStreamWriter(getOutputStream(filePath, conf), encoder);
        return new BufferedWriter(writer);
    }

    private static FSDataOutputStream getOutputStream(String filePath, Configuration conf) {
        Path path = new Path(filePath);
        FileSystem fs;
        FSDataOutputStream outputStream;
        try {
            fs = path.getFileSystem(conf);
            outputStream = fs.create(path, true, 1024);
        } catch (IOException e) {
            throw new RuntimeException(e.getCause());
        }

        return outputStream;
    }

    public final static FSDataInputStream buildInputStream(String filePath, Configuration conf) throws IOException {
        Path path = new Path(filePath);
        return buildInputStream(path, conf);
    }

    public final static FSDataInputStream buildInputStream(Path path, Configuration conf) throws IOException {
        return buildInputStream(path, DEFAULT_BUFFER_SIZE, conf);
    }

    public final static FSDataInputStream buildInputStream(Path path, int bufferSize, Configuration conf)
        throws IOException {
        try {
            FileSystem fs = path.getFileSystem(conf);
            return fs.open(path, bufferSize);
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
            throw new RuntimeException(e.getCause());
        }
    }

    public final static FSDataInputStream buildInputStream(FileSystem fs, Path path, int bufferSize)
        throws IOException {
        return fs.open(path, bufferSize);
    }

    public interface HadoopFileChannel extends WritableByteChannel {
        public void flush() throws IOException;
    }

    public static HadoopFileChannel newFilesystemChannel(String filePath, Configuration conf) {
        Path path = new Path(filePath);
        FileSystem fs;
        FSDataOutputStream outputStream;
        try {
            fs = path.getFileSystem(conf);
            outputStream = fs.create(path, true, 1024);
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
            throw new RuntimeException(e.getCause());
        }

        return new HadoopFileChannel()
        {
            @Override
            public boolean isOpen()
            {
                return true;
            }

            @Override
            public int write(ByteBuffer src) throws IOException
            {
                int toWrite = src.remaining();

                if (src.hasArray())
                {
                    outputStream.write(src.array(), src.arrayOffset() + src.position(), src.remaining());
                    src.position(src.limit());
                    return toWrite;
                }

                if (toWrite < 16)
                {
                    int offset = src.position();
                    for (int i = 0 ; i < toWrite ; i++)
                        outputStream.write(src.get(i + offset));
                    src.position(src.limit());
                    return toWrite;
                }

                byte[] buf = retrieveTemporaryBuffer(toWrite);

                int totalWritten = 0;
                while (totalWritten < toWrite)
                {
                    int toWriteThisTime = Math.min(buf.length, toWrite - totalWritten);

                    org.apache.cassandra.utils.ByteBufferUtil.arrayCopy(src, src.position() + totalWritten, buf, 0, toWriteThisTime);

                    outputStream.write(buf, 0, toWriteThisTime);

                    totalWritten += toWriteThisTime;
                }

                src.position(src.limit());
                return totalWritten;
            }

            public void flush() throws IOException {
                outputStream.hsync();
            }

            @Override
            public void close() throws IOException {
                flush();
                outputStream.close();
            }
        };
    }

    private static final FastThreadLocal<byte[]> tempBuffer = new FastThreadLocal<byte[]>()
    {
        @Override
        public byte[] initialValue()
        {
            return new byte[16];
        }
    };

    private static int MAX_BUFFER_SIZE =
        Integer.getInteger(Config.PROPERTY_PREFIX + "data_output_stream_plus_temp_buffer_size", 8192);

    /*
     * Factored out into separate method to create more flexibility around inlining
     */
    public static byte[] retrieveTemporaryBuffer(int minSize)
    {
        byte[] bytes = tempBuffer.get();
        if (bytes.length < Math.min(minSize, MAX_BUFFER_SIZE))
        {
            // increase in powers of 2, to avoid wasted repeat allocations
            bytes = new byte[Math.min(MAX_BUFFER_SIZE, 2 * Integer.highestOneBit(minSize))];
            tempBuffer.set(bytes);
        }
        return bytes;
    }

    public static List<String> readLines(String filename, Configuration conf) throws IOException {
        filename = HadoopFileUtils.normalizeFileName(filename);
        Path path = new Path(filename);
        BufferedReader in = null;
        try {
            List<String> responseData = new ArrayList<String>();
            FileSystem fs = path.getFileSystem(conf);

            FSDataInputStream inputStream = fs.open(path);
            in = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = in.readLine()) != null) {
                responseData.add(line);
            }

            return responseData;
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
            throw e;
        } finally {
            closeWithExceptionThrow(in);
        }
    }

    public static long fileSize(String filename, Configuration conf) throws IOException {
        filename = HadoopFileUtils.normalizeFileName(filename);
        Path path = new Path(filename);

        try {
            FileSystem fs = path.getFileSystem(conf);
            FileStatus fileStatus = fs.getFileStatus(path);
            return fileStatus.getLen();
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
            throw e;
        }
    }

    private static void closeWithExceptionThrow(Closeable closeable) throws IOException {
        if (closeable == null)
                return;

        closeable.close();
    }
}
