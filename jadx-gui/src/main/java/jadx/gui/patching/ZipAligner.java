package jadx.gui.patching;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.utils.exceptions.JadxRuntimeException;

public class ZipAligner {
	private static final Logger LOG = LoggerFactory.getLogger(ZipAligner.class);

	public static final int DEFAULT_ALIGNMENT = 4;
	public static final int PAGE_ALIGNMENT = 4096;

	private static final int LFH_SIGNATURE = 0x04034b50;
	private static final int CD_SIGNATURE = 0x02014b50;
	private static final int EOCD_SIGNATURE = 0x06054b50;

	public static void align(Path inputZip, Path outputZip) {
		align(inputZip, outputZip, DEFAULT_ALIGNMENT);
	}

	public static void align(Path inputZip, Path outputZip, int defaultAlignment) {
		if (!Files.exists(inputZip)) {
			throw new IllegalArgumentException("Input ZIP does not exist: " + inputZip);
		}

		Path tempOutput = null;
		try {
			Path parentDir = outputZip.getParent();
			if (parentDir != null) {
				Files.createDirectories(parentDir);
			}
			tempOutput = Files.createTempFile("jadx_align_", ".apk");

			try (RandomAccessFile rafIn = new RandomAccessFile(inputZip.toFile(), "r");
					OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempOutput))) {

				List<InputEntry> inputEntries = parseZipEntries(rafIn);
				long currentOutputOffset = 0;
				List<OutputCdEntry> cdEntries = new ArrayList<>();

				byte[] buffer = new byte[16384];

				for (InputEntry inEntry : inputEntries) {
					int alignment = defaultAlignment;
					if (inEntry.name.endsWith(".so") && inEntry.method == 0) {
						alignment = PAGE_ALIGNMENT;
					}

					int padding = 0;
					if (inEntry.method == 0) { // STORED
						long dataOffsetWithoutPadding = currentOutputOffset + 30 + inEntry.nameBytes.length + inEntry.extraBytes.length;
						int remainder = (int) (dataOffsetWithoutPadding % alignment);
						if (remainder != 0) {
							padding = alignment - remainder;
						}
					}

					byte[] paddedExtra = new byte[inEntry.extraBytes.length + padding];
					if (inEntry.extraBytes.length > 0) {
						System.arraycopy(inEntry.extraBytes, 0, paddedExtra, 0, inEntry.extraBytes.length);
					}

					long entryLfhOffset = currentOutputOffset;

					// 1. Write Local File Header
					byte[] lfh = createLocalFileHeader(inEntry, inEntry.nameBytes.length, paddedExtra.length);
					out.write(lfh);
					out.write(inEntry.nameBytes);
					out.write(paddedExtra);
					currentOutputOffset += lfh.length + inEntry.nameBytes.length + paddedExtra.length;

					// 2. Stream raw compressed/stored payload directly from input file
					rafIn.seek(inEntry.dataOffset);
					long remaining = inEntry.compressedSize;
					while (remaining > 0) {
						int toRead = (int) Math.min(buffer.length, remaining);
						int read = rafIn.read(buffer, 0, toRead);
						if (read == -1) {
							throw new IOException("Unexpected EOF while reading raw entry payload: " + inEntry.name);
						}
						out.write(buffer, 0, read);
						currentOutputOffset += read;
						remaining -= read;
					}

					cdEntries.add(new OutputCdEntry(inEntry, paddedExtra, entryLfhOffset));
				}

				// 3. Write Central Directory
				long cdStartOffset = currentOutputOffset;
				for (OutputCdEntry cdEntry : cdEntries) {
					byte[] cdRecord = createCentralDirectoryRecord(cdEntry);
					out.write(cdRecord);
					out.write(cdEntry.input.nameBytes);
					out.write(cdEntry.paddedExtra);
					if (cdEntry.input.commentBytes != null && cdEntry.input.commentBytes.length > 0) {
						out.write(cdEntry.input.commentBytes);
					}
					currentOutputOffset += cdRecord.length + cdEntry.input.nameBytes.length
							+ cdEntry.paddedExtra.length + (cdEntry.input.commentBytes != null ? cdEntry.input.commentBytes.length : 0);
				}
				long cdSize = currentOutputOffset - cdStartOffset;

				// 4. Write EOCD
				byte[] eocd = createEocd(cdEntries.size(), cdSize, cdStartOffset);
				out.write(eocd);
			}

			Files.move(tempOutput, outputZip, StandardCopyOption.REPLACE_EXISTING);
			LOG.info("Aligned APK written successfully to: {}", outputZip);
		} catch (Exception e) {
			if (tempOutput != null) {
				try {
					Files.deleteIfExists(tempOutput);
				} catch (IOException ignored) {
				}
			}
			throw new JadxRuntimeException("Failed to zipalign APK: " + e.getMessage(), e);
		}
	}

	private static List<InputEntry> parseZipEntries(RandomAccessFile raf) throws IOException {
		long length = raf.length();
		if (length < 22) {
			throw new IOException("File too short to be a valid ZIP archive");
		}

		// Find EOCD (search backwards in last 65KB)
		long searchStart = Math.max(0, length - 65557);
		long eocdOffset = -1;
		for (long i = length - 22; i >= searchStart; i--) {
			raf.seek(i);
			if (Integer.reverseBytes(raf.readInt()) == EOCD_SIGNATURE) {
				eocdOffset = i;
				break;
			}
		}
		if (eocdOffset == -1) {
			throw new IOException("End of Central Directory signature not found");
		}

		raf.seek(eocdOffset + 10);
		int totalEntries = Short.reverseBytes(raf.readShort()) & 0xFFFF;
		int cdSize = Integer.reverseBytes(raf.readInt());
		long cdOffset = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;

		List<InputEntry> entries = new ArrayList<>(totalEntries);
		raf.seek(cdOffset);

		for (int i = 0; i < totalEntries; i++) {
			int sig = Integer.reverseBytes(raf.readInt());
			if (sig != CD_SIGNATURE) {
				throw new IOException("Expected Central Directory signature at offset " + (raf.getFilePointer() - 4));
			}

			short versionMadeBy = Short.reverseBytes(raf.readShort());
			short versionNeeded = Short.reverseBytes(raf.readShort());
			short flags = Short.reverseBytes(raf.readShort());
			short method = Short.reverseBytes(raf.readShort());
			int time = Integer.reverseBytes(raf.readInt());
			int crc = Integer.reverseBytes(raf.readInt());
			long compressedSize = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;
			long uncompressedSize = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;
			int nameLen = Short.reverseBytes(raf.readShort()) & 0xFFFF;
			int extraLen = Short.reverseBytes(raf.readShort()) & 0xFFFF;
			int commentLen = Short.reverseBytes(raf.readShort()) & 0xFFFF;
			short diskStart = Short.reverseBytes(raf.readShort());
			short internalAttr = Short.reverseBytes(raf.readShort());
			int externalAttr = Integer.reverseBytes(raf.readInt());
			long lfhOffset = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;

			byte[] nameBytes = new byte[nameLen];
			raf.readFully(nameBytes);
			String name = new String(nameBytes, StandardCharsets.UTF_8);

			byte[] extraBytes = new byte[extraLen];
			raf.readFully(extraBytes);

			byte[] commentBytes = new byte[commentLen];
			raf.readFully(commentBytes);

			long nextCdPos = raf.getFilePointer();

			// Read LFH to determine actual raw data offset
			raf.seek(lfhOffset);
			int lfhSig = Integer.reverseBytes(raf.readInt());
			if (lfhSig != LFH_SIGNATURE) {
				throw new IOException("Invalid Local File Header signature for entry: " + name);
			}
			raf.seek(lfhOffset + 26);
			int lfhNameLen = Short.reverseBytes(raf.readShort()) & 0xFFFF;
			int lfhExtraLen = Short.reverseBytes(raf.readShort()) & 0xFFFF;
			long dataOffset = lfhOffset + 30 + lfhNameLen + lfhExtraLen;

			entries.add(new InputEntry(name, nameBytes, extraBytes, commentBytes, versionMadeBy, versionNeeded,
					flags, method, time, crc, compressedSize, uncompressedSize, diskStart, internalAttr,
					externalAttr, dataOffset));

			raf.seek(nextCdPos);
		}

		return entries;
	}

	private static byte[] createLocalFileHeader(InputEntry in, int nameLen, int extraLen) {
		ByteBuffer buf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(LFH_SIGNATURE);
		buf.putShort(in.versionNeeded);
		buf.putShort((short) (in.flags & ~0x0008)); // Clear data descriptor flag since sizes/crc are in header
		buf.putShort(in.method);
		buf.putInt(in.time);
		buf.putInt(in.crc);
		buf.putInt((int) in.compressedSize);
		buf.putInt((int) in.uncompressedSize);
		buf.putShort((short) nameLen);
		buf.putShort((short) extraLen);
		return buf.array();
	}

	private static byte[] createCentralDirectoryRecord(OutputCdEntry cd) {
		ByteBuffer buf = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(CD_SIGNATURE);
		buf.putShort(cd.input.versionMadeBy);
		buf.putShort(cd.input.versionNeeded);
		buf.putShort((short) (cd.input.flags & ~0x0008)); // Clear data descriptor flag
		buf.putShort(cd.input.method);
		buf.putInt(cd.input.time);
		buf.putInt(cd.input.crc);
		buf.putInt((int) cd.input.compressedSize);
		buf.putInt((int) cd.input.uncompressedSize);
		buf.putShort((short) cd.input.nameBytes.length);
		buf.putShort((short) cd.paddedExtra.length);
		buf.putShort((short) (cd.input.commentBytes != null ? cd.input.commentBytes.length : 0));
		buf.putShort(cd.input.diskStart);
		buf.putShort(cd.input.internalAttr);
		buf.putInt(cd.input.externalAttr);
		buf.putInt((int) cd.lfhOffset);
		return buf.array();
	}

	private static byte[] createEocd(int totalEntries, long cdSize, long cdOffset) {
		ByteBuffer buf = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(EOCD_SIGNATURE);
		buf.putShort((short) 0);
		buf.putShort((short) 0);
		buf.putShort((short) totalEntries);
		buf.putShort((short) totalEntries);
		buf.putInt((int) cdSize);
		buf.putInt((int) cdOffset);
		buf.putShort((short) 0);
		return buf.array();
	}

	private static class InputEntry {
		final String name;
		final byte[] nameBytes;
		final byte[] extraBytes;
		final byte[] commentBytes;
		final short versionMadeBy;
		final short versionNeeded;
		final short flags;
		final short method;
		final int time;
		final int crc;
		final long compressedSize;
		final long uncompressedSize;
		final short diskStart;
		final short internalAttr;
		final int externalAttr;
		final long dataOffset;

		InputEntry(String name, byte[] nameBytes, byte[] extraBytes, byte[] commentBytes,
				short versionMadeBy, short versionNeeded, short flags, short method, int time, int crc,
				long compressedSize, long uncompressedSize, short diskStart, short internalAttr,
				int externalAttr, long dataOffset) {
			this.name = name;
			this.nameBytes = nameBytes;
			this.extraBytes = extraBytes;
			this.commentBytes = commentBytes;
			this.versionMadeBy = versionMadeBy;
			this.versionNeeded = versionNeeded;
			this.flags = flags;
			this.method = method;
			this.time = time;
			this.crc = crc;
			this.compressedSize = compressedSize;
			this.uncompressedSize = uncompressedSize;
			this.diskStart = diskStart;
			this.internalAttr = internalAttr;
			this.externalAttr = externalAttr;
			this.dataOffset = dataOffset;
		}
	}

	private static class OutputCdEntry {
		final InputEntry input;
		final byte[] paddedExtra;
		final long lfhOffset;

		OutputCdEntry(InputEntry input, byte[] paddedExtra, long lfhOffset) {
			this.input = input;
			this.paddedExtra = paddedExtra;
			this.lfhOffset = lfhOffset;
		}
	}
}
