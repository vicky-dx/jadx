package jadx.gui.patching.history;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

public class PatchCommit {
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.systemDefault());

	private final String fullHash;
	private final String shortHash;
	private final long timestamp;
	private final String author;
	private final String message;
	private final String tagName;
	private final List<String> affectedClasses;

	public PatchCommit(String fullHash, long timestamp, String author,
			String message, String tagName, List<String> affectedClasses) {
		this.fullHash = fullHash != null ? fullHash : "";
		this.shortHash = (this.fullHash.length() >= 7) ? this.fullHash.substring(0, 7) : this.fullHash;
		this.timestamp = timestamp;
		this.author = author != null ? author : "JADX";
		this.message = message != null ? message : "";
		this.tagName = tagName;
		this.affectedClasses = affectedClasses != null ? affectedClasses : Collections.emptyList();
	}

	public String getFullHash() {
		return fullHash;
	}

	public String getShortHash() {
		return shortHash;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public String getAuthor() {
		return author;
	}

	public String getMessage() {
		return message;
	}

	public String getTagName() {
		return tagName;
	}

	public List<String> getAffectedClasses() {
		return affectedClasses;
	}

	public boolean isBaseline() {
		return message.startsWith("[baseline]");
	}

	public boolean isDeployMilestone() {
		return (tagName != null && tagName.startsWith("deploy-")) || message.startsWith("[deploy]");
	}

	public boolean isExportMilestone() {
		return (tagName != null && tagName.startsWith("export-")) || message.startsWith("[export]");
	}

	public boolean isReloadSnapshot() {
		return (tagName != null && tagName.startsWith("reload-")) || message.contains("[snapshot-before-reload]");
	}

	public String getFormattedTime() {
		try {
			return DATE_FORMAT.format(Instant.ofEpochMilli(timestamp));
		} catch (Exception e) {
			return String.valueOf(timestamp);
		}
	}

	@Override
	public String toString() {
		return "[" + shortHash + "] " + message + " (" + getFormattedTime() + ")";
	}
}
