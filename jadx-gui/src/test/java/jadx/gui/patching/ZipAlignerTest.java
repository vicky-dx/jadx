package jadx.gui.patching;

import java.io.BufferedOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

public class ZipAlignerTest {

	@Test
	public void testStoredEntriesAreAlignedTo4Bytes(@TempDir Path tempDir) throws Exception {
		Path inputZip = tempDir.resolve("input.zip");
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(inputZip)))) {
			// Write 3 stored entries of odd lengths
			for (int i = 1; i <= 3; i++) {
				byte[] data = new byte[i * 7]; // odd length
				ZipEntry entry = new ZipEntry("stored_file_" + i + ".bin");
				entry.setMethod(ZipEntry.STORED);
				entry.setSize(data.length);
				entry.setCompressedSize(data.length);
				CRC32 crc = new CRC32();
				crc.update(data);
				entry.setCrc(crc.getValue());
				zos.putNextEntry(entry);
				zos.write(data);
				zos.closeEntry();
			}
		}

		Path alignedZip = tempDir.resolve("aligned.zip");
		ZipAligner.align(inputZip, alignedZip);

		assertThat(Files.exists(alignedZip)).isTrue();

		// Inspect all LFH data offsets in aligned zip
		try (RandomAccessFile raf = new RandomAccessFile(alignedZip.toFile(), "r")) {
			long len = raf.length();
			long offset = 0;
			int entryCount = 0;
			while (offset < len) {
				raf.seek(offset);
				int sig = Integer.reverseBytes(raf.readInt());
				if (sig != 0x04034b50) { // Not LFH
					break;
				}
				raf.seek(offset + 8);
				short method = Short.reverseBytes(raf.readShort());
				raf.seek(offset + 18);
				long compressedSize = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;
				raf.seek(offset + 26);
				int nameLen = Short.reverseBytes(raf.readShort()) & 0xFFFF;
				int extraLen = Short.reverseBytes(raf.readShort()) & 0xFFFF;

				long dataOffset = offset + 30 + nameLen + extraLen;
				if (method == ZipEntry.STORED) {
					assertThat(dataOffset % 4)
							.as("Data offset for STORED entry at " + dataOffset + " must be a multiple of 4")
							.isEqualTo(0);
				}

				offset = dataOffset + compressedSize;
				entryCount++;
			}
			assertThat(entryCount).isEqualTo(3);
		}
	}
}
