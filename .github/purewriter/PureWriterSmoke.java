import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import net.sf.sevenzipjbinding.ExtractAskMode;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IArchiveExtractCallback;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.IOutCreateArchive7z;
import net.sf.sevenzipjbinding.IOutCreateCallback;
import net.sf.sevenzipjbinding.IOutItem7z;
import net.sf.sevenzipjbinding.ISequentialInStream;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.OutItemFactory;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream;

/**
 * Pure Writer smoke test of a 7-Zip-JBinding build: compresses the files of a directory with the settings of a Pure
 * Writer backup (7z, level 5, 1 MiB dictionary, default thread count, non-solid), extracts the archive again with the
 * same build and compares every member byte by byte. The archive format is checked separately with py7zr (smoke.py).
 *
 * Usage: PureWriterSmoke &lt;input directory&gt; &lt;archive&gt; &lt;extract directory&gt;
 */
public final class PureWriterSmoke {
    static final long DICTIONARY_SIZE = 1 << 20;

    public static void main(String[] args) throws Exception {
        File[] inputs = new File(args[0]).listFiles();
        Arrays.sort(inputs);
        Path archive = Paths.get(args[1]);
        Path extracted = Paths.get(args[2]).toAbsolutePath();

        SevenZip.initSevenZipFromPlatformJAR();
        System.out.println("platform=" + SevenZip.getUsedPlatform() + " os.arch=" + System.getProperty("os.arch")
                + " jbinding=" + SevenZip.getSevenZipJBindingVersion());

        long start = System.nanoTime();
        try (RandomAccessFile output = new RandomAccessFile(archive.toFile(), "rw");
                IOutCreateArchive7z outArchive = SevenZip.openOutArchive7z();
                Create callback = new Create(inputs)) {
            outArchive.setLevel(5);
            outArchive.setDictionarySize(DICTIONARY_SIZE);
            outArchive.setSolid(false);
            outArchive.createArchive(new RandomAccessFileOutStream(output), inputs.length, callback);
        }
        System.out.printf("compressed %d members into %d bytes in %.2f s%n", inputs.length, Files.size(archive),
                (System.nanoTime() - start) / 1e9);

        Files.createDirectories(extracted);
        try (RandomAccessFile input = new RandomAccessFile(archive.toFile(), "r");
                IInArchive inArchive = SevenZip.openInArchive(null, new RandomAccessFileInStream(input));
                Extract callback = new Extract(inArchive, extracted)) {
            if (inArchive.getNumberOfItems() != inputs.length) {
                throw new AssertionError("Member count: " + inArchive.getNumberOfItems());
            }
            inArchive.extract(null, false, callback);
            for (int i = 0; i < inArchive.getNumberOfItems(); i++) {
                System.out.println("member " + inArchive.getProperty(i, PropID.PATH) + " method="
                        + inArchive.getProperty(i, PropID.METHOD));
            }
        }
        for (File file : inputs) {
            byte[] expected = Files.readAllBytes(file.toPath());
            byte[] actual = Files.readAllBytes(extracted.resolve(file.getName()));
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError("Extracted member differs: " + file.getName());
            }
        }
        System.out.println("extracted members are identical");
    }

    private static final class Create implements IOutCreateCallback<IOutItem7z>, AutoCloseable {
        private final File[] files;
        private final List<RandomAccessFile> streams = new ArrayList<RandomAccessFile>();

        Create(File[] files) {
            this.files = files;
        }

        public void setTotal(long total) {
        }

        public void setCompleted(long complete) {
        }

        public void setOperationResult(boolean operationResultOk) throws SevenZipException {
            if (!operationResultOk) {
                throw new SevenZipException("Compression failed");
            }
        }

        public IOutItem7z getItemInformation(int index, OutItemFactory<IOutItem7z> outItemFactory) {
            IOutItem7z item = outItemFactory.createOutItem();
            item.setDataSize(files[index].length());
            item.setPropertyPath(files[index].getName());
            item.setPropertyLastModificationTime(new Date(files[index].lastModified()));
            return item;
        }

        public ISequentialInStream getStream(int index) throws SevenZipException {
            try {
                RandomAccessFile stream = new RandomAccessFile(files[index], "r");
                streams.add(stream);
                return new RandomAccessFileInStream(stream);
            } catch (IOException e) {
                throw new SevenZipException(e);
            }
        }

        public void close() throws IOException {
            for (RandomAccessFile stream : streams) {
                stream.close();
            }
        }
    }

    private static final class Extract implements IArchiveExtractCallback, AutoCloseable {
        private final IInArchive archive;
        private final Path target;
        private final List<OutputStream> streams = new ArrayList<OutputStream>();

        Extract(IInArchive archive, Path target) {
            this.archive = archive;
            this.target = target;
        }

        public void setTotal(long total) {
        }

        public void setCompleted(long complete) {
        }

        public void prepareOperation(ExtractAskMode extractAskMode) {
        }

        public void setOperationResult(ExtractOperationResult result) throws SevenZipException {
            if (result != ExtractOperationResult.OK) {
                throw new SevenZipException("Extraction failed: " + result);
            }
        }

        public ISequentialOutStream getStream(int index, ExtractAskMode extractAskMode) throws SevenZipException {
            if (extractAskMode != ExtractAskMode.EXTRACT) {
                return null;
            }
            try {
                Path path = target.resolve((String) archive.getProperty(index, PropID.PATH)).normalize();
                if (!target.equals(path.getParent())) {
                    throw new IOException("Unexpected member path: " + path);
                }
                final OutputStream stream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW);
                streams.add(stream);
                return new ISequentialOutStream() {
                    public int write(byte[] data) throws SevenZipException {
                        try {
                            stream.write(data);
                            return data.length;
                        } catch (IOException e) {
                            throw new SevenZipException(e);
                        }
                    }
                };
            } catch (IOException e) {
                throw new SevenZipException(e);
            }
        }

        public void close() throws IOException {
            for (OutputStream stream : streams) {
                stream.close();
            }
        }
    }
}
