package jadx.gui.patching.history;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PatchHistoryManager {
	private static final Logger LOG = LoggerFactory.getLogger(PatchHistoryManager.class);
	private static final PatchHistoryManager INSTANCE = new PatchHistoryManager();

	private static final PersonIdent IDENT = new PersonIdent("JADX Patching", "patcher@jadx.gui");

	private Git git;
	private Repository repo;
	private Path workingDir;
	private boolean initialized = false;

	private final Map<String, Boolean> baselineRecorded = new ConcurrentHashMap<>();
	// Bug 6 fix: cache baseline smali per class to avoid O(N²) git log walk on every Ctrl+S
	private final Map<String, String> baselineSmaliCache = new ConcurrentHashMap<>();

	public static PatchHistoryManager getInstance() {
		return INSTANCE;
	}

	private PatchHistoryManager() {
	}

	public synchronized boolean isInitialized() {
		return initialized && git != null && repo != null;
	}

	public synchronized Path getWorkingDir() {
		return workingDir;
	}

	private boolean isTempRepo = false;

	public synchronized boolean isPersistent() {
		return isInitialized() && !isTempRepo;
	}

	public synchronized boolean init(Path baseDir) {
		try {
			close();
			if (baseDir == null) {
				workingDir = Files.createTempDirectory("jadx-patch-history-");
				workingDir.toFile().deleteOnExit();
				isTempRepo = true;
			} else {
				// If baseDir already ends with .history or is the target history directory, use directly,
				// otherwise use baseDir.resolve(".jadx-patch-history")
				if (baseDir.getFileName().toString().endsWith(".history")
						|| baseDir.getFileName().toString().equals(".jadx-patch-history")) {
					workingDir = baseDir;
				} else {
					workingDir = baseDir.resolve(".jadx-patch-history");
				}
				isTempRepo = false;
			}
			Files.createDirectories(workingDir);
			File gitDir = workingDir.resolve(".git").toFile();
			if (!gitDir.exists()) {
				git = Git.init().setDirectory(workingDir.toFile()).call();
			} else {
				git = Git.open(workingDir.toFile());
			}
			repo = git.getRepository();
			initialized = true;
			baselineRecorded.clear();
			baselineSmaliCache.clear();
			LOG.info("Initialized Git patch history at: {} (persistent={})", workingDir, !isTempRepo);
			return true;
		} catch (Exception e) {
			LOG.error("Failed to initialize Git patch history", e);
			initialized = false;
			return false;
		}
	}

	/**
	 * Migrates the current Git patch history repository to a new target directory.
	 * Used when saving a project so that the history is stored beside the project file.
	 */
	public synchronized boolean migrateTo(Path targetHistoryDir) {
		if (!isInitialized()) {
			return init(targetHistoryDir);
		}
		if (workingDir != null && workingDir.toAbsolutePath().equals(targetHistoryDir.toAbsolutePath())) {
			isTempRepo = false;
			return true;
		}
		Path oldWorkingDir = workingDir;
		try {
			close();
			Files.createDirectories(targetHistoryDir);
			copyDirectoryRecursively(oldWorkingDir, targetHistoryDir);
			workingDir = targetHistoryDir;
			isTempRepo = false;
			git = Git.open(workingDir.toFile());
			repo = git.getRepository();
			initialized = true;
			LOG.info("Successfully migrated patch history repository from {} to {}", oldWorkingDir, targetHistoryDir);
			return true;
		} catch (Exception e) {
			LOG.error("Failed to migrate patch history from {} to {}", oldWorkingDir, targetHistoryDir, e);
			// Try to recover by reopening original workingDir
			try {
				if (oldWorkingDir != null && Files.exists(oldWorkingDir)) {
					git = Git.open(oldWorkingDir.toFile());
					repo = git.getRepository();
					workingDir = oldWorkingDir;
					initialized = true;
				}
			} catch (Exception recEx) {
				LOG.error("Failed to recover old history repo", recEx);
			}
			return false;
		}
	}

	private synchronized boolean ensureInitialized() {
		if (!isInitialized()) {
			return init(null);
		}
		return true;
	}

	public static String classTypeToFilePath(String classType) {
		if (classType == null || classType.trim().isEmpty()) {
			return "classes/Unknown.smali";
		}
		String clean = classType.trim();
		if (clean.startsWith("L") && clean.endsWith(";")) {
			clean = clean.substring(1, clean.length() - 1);
		}
		// Bug 4 fix: keep '.' -> '/' but keep '$' as '__' separator to avoid collision
		// between com.example.Foo$Bar and com.example.Foo_Bar (both mapped to same path before).
		// Git handles '$' in file paths fine on all platforms.
		clean = clean.replace('.', '/');
		// Replace $ with a separator that cannot occur in valid Java identifiers
		clean = clean.replace('$', '+');
		return "classes/" + clean + ".smali";
	}

	public static String filePathToClassType(String relPath) {
		if (relPath == null) {
			return "";
		}
		String clean = relPath.replace('\\', '/');
		if (clean.startsWith("classes/")) {
			clean = clean.substring("classes/".length());
		}
		if (clean.endsWith(".smali")) {
			clean = clean.substring(0, clean.length() - ".smali".length());
		}
		clean = clean.replace('+', '$');
		clean = clean.replace('/', '.');
		return clean;
	}

	public static String normalizeClassType(String classType) {
		if (classType == null) {
			return "";
		}
		String clean = classType.trim();
		if (clean.startsWith("L") && clean.endsWith(";")) {
			clean = clean.substring(1, clean.length() - 1);
		}
		clean = clean.replace('/', '.');
		return clean;
	}

	public static String canonicalClassType(String classType) {
		if (classType == null) {
			return "";
		}
		String clean = normalizeClassType(classType);
		if (clean.startsWith(jadx.core.Consts.DEFAULT_PACKAGE_NAME + ".")) {
			clean = clean.substring(jadx.core.Consts.DEFAULT_PACKAGE_NAME.length() + 1);
		}
		return clean;
	}

	public static List<String> getPossibleFilePaths(String classType) {
		List<String> paths = new ArrayList<>(2);
		String primary = classTypeToFilePath(classType);
		paths.add(primary);

		String norm = normalizeClassType(classType);
		if (norm.startsWith(jadx.core.Consts.DEFAULT_PACKAGE_NAME + ".")) {
			String raw = norm.substring(jadx.core.Consts.DEFAULT_PACKAGE_NAME.length() + 1);
			String rawPath = "classes/" + raw.replace('$', '+') + ".smali";
			if (!paths.contains(rawPath)) {
				paths.add(rawPath);
			}
		} else if (!norm.contains(".")) {
			String defPath = "classes/" + jadx.core.Consts.DEFAULT_PACKAGE_NAME + "/" + norm.replace('$', '+') + ".smali";
			if (!paths.contains(defPath)) {
				paths.add(defPath);
			}
		}
		return paths;
	}

	public synchronized String getExistingTrackedPath(String classType) {
		for (String path : getPossibleFilePaths(classType)) {
			if (baselineRecorded.containsKey(path) || isPathTracked(path)) {
				return path;
			}
		}
		return classTypeToFilePath(classType);
	}

	public static String getRootClassName(String classType) {
		if (classType == null) {
			return "";
		}
		String norm = normalizeClassType(classType);
		int idx = norm.indexOf('$');
		if (idx != -1) {
			return norm.substring(0, idx);
		}
		return norm;
	}

	public static class ModifiedClassSummary {
		private final String classType;
		private String dexName;
		private int checkpointCount;
		private long lastEditTime;
		private String lastCommitHash;
		private String lastMessage;
		private boolean inMemoryModified;
		private final List<String> innerClasses = new ArrayList<>();

		public ModifiedClassSummary(String classType, String dexName) {
			this.classType = classType;
			this.dexName = (dexName != null && !dexName.isEmpty()) ? dexName : "classes.dex";
		}

		public String getClassType() {
			return classType;
		}

		public String getDexName() {
			return dexName;
		}

		public void setDexName(String dexName) {
			this.dexName = dexName;
		}

		public int getCheckpointCount() {
			return checkpointCount;
		}

		public void setCheckpointCount(int checkpointCount) {
			this.checkpointCount = checkpointCount;
		}

		public void incrementCheckpointCount() {
			this.checkpointCount++;
		}

		public long getLastEditTime() {
			return lastEditTime;
		}

		public void setLastEditTime(long lastEditTime) {
			this.lastEditTime = lastEditTime;
		}

		public void setLastCommitTime(long lastCommitTime) {
			this.lastEditTime = lastCommitTime;
		}

		public String getLastCommitHash() {
			return lastCommitHash;
		}

		public void setLastCommitHash(String lastCommitHash) {
			this.lastCommitHash = lastCommitHash;
		}

		public String getLastMessage() {
			return lastMessage;
		}

		public void setLastMessage(String lastMessage) {
			this.lastMessage = lastMessage;
		}

		public boolean isInMemoryModified() {
			return inMemoryModified;
		}

		public void setInMemoryModified(boolean inMemoryModified) {
			this.inMemoryModified = inMemoryModified;
		}

		public List<String> getInnerClasses() {
			return Collections.unmodifiableList(innerClasses);
		}

		public void addInnerClass(String innerCls) {
			if (innerCls != null && !innerCls.equals(classType) && !innerClasses.contains(innerCls)) {
				innerClasses.add(innerCls);
			}
		}

		public String getInnerClassesSummary() {
			if (innerClasses.isEmpty()) {
				return "";
			}
			return "  (Includes " + innerClasses.size() + " inner class" + (innerClasses.size() == 1 ? "" : "es") + ")";
		}
	}

	/**
	 * Single-pass O(C + M) aggregation of all modified classes across active session and Git history.
	 * Automatically groups inner classes ($) under their root parent class.
	 * Returns map of (normalizedClassType -> ModifiedClassSummary).
	 */
	public synchronized Map<String, ModifiedClassSummary> getModifiedClassesOverview() {
		Map<String, ModifiedClassSummary> result = new LinkedHashMap<>();

		// 1. In-memory modified classes in O(M) grouped by root class
		try {
			List<jadx.gui.patching.ModifiedClass> inMemory = jadx.gui.patching.ModifiedDexManager.getInstance().getModifiedClasses();
			for (jadx.gui.patching.ModifiedClass mc : inMemory) {
				String norm = normalizeClassType(mc.getClassType());
				String root = getRootClassName(norm);
				String key = canonicalClassType(root);
				ModifiedClassSummary summary = result.get(key);
				if (summary == null) {
					summary = new ModifiedClassSummary(root, mc.getDexName());
					result.put(key, summary);
				}
				summary.setInMemoryModified(true);
				summary.setLastEditTime(Math.max(summary.getLastEditTime(), mc.getModifiedTimestamp()));
				if (!root.equals(norm)) {
					summary.addInnerClass(norm);
				}
			}
		} catch (Exception e) {
			LOG.debug("Error querying ModifiedDexManager", e);
		}

		if (!isInitialized()) {
			return result;
		}

		// 2. Single-pass Git RevWalk in O(C)
		try {
			ObjectId head = repo.resolve(Constants.HEAD);
			if (head != null) {
				try (RevWalk walk = new RevWalk(repo)) {
					walk.markStart(walk.parseCommit(head));
					RevCommit commit;
					while ((commit = walk.next()) != null) {
						List<String> touchedPaths = getTouchedPathsInCommit(commit);
						for (String relPath : touchedPaths) {
							if (relPath.startsWith("classes/") && relPath.endsWith(".smali")) {
								String cls = filePathToClassType(relPath);
								String root = getRootClassName(cls);
								String key = canonicalClassType(root);
								ModifiedClassSummary summary = result.get(key);
								if (summary == null) {
									summary = new ModifiedClassSummary(root, "classes.dex");
									result.put(key, summary);
								}
								if (!root.equals(cls)) {
									summary.addInnerClass(cls);
								}
								summary.incrementCheckpointCount();
								if (summary.getLastCommitHash() == null) {
									summary.setLastCommitHash(commit.name().substring(0, Math.min(7, commit.name().length())));
									summary.setLastCommitTime(commit.getCommitTime() * 1000L);
									summary.setLastMessage(commit.getFullMessage());
								}
							}
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.error("Failed to build modified classes overview", e);
		}

		// Filter out classes that were only viewed/baselined without any actual user edits
		result.entrySet().removeIf(entry -> {
			ModifiedClassSummary summary = entry.getValue();
			if (summary.isInMemoryModified()) {
				return false; // keep active in-memory modified classes
			}
			return summary.getCheckpointCount() <= 1;
		});

		return result;
	}

	public synchronized boolean isClassTracked(String classType) {
		if (classType == null) {
			return false;
		}
		for (String path : getPossibleFilePaths(classType)) {
			if (isPathTracked(path)) {
				return true;
			}
		}
		return false;
	}

	private List<String> getTouchedPathsInCommit(RevCommit commit) {
		List<String> paths = new ArrayList<>();
		try {
			if (commit.getParentCount() == 0) {
				try (TreeWalk tw = new TreeWalk(repo)) {
					tw.addTree(commit.getTree());
					tw.setRecursive(true);
					while (tw.next()) {
						paths.add(tw.getPathString());
					}
				}
			} else {
				RevCommit parent = commit.getParent(0);
				try (TreeWalk tw = new TreeWalk(repo)) {
					tw.addTree(parent.getTree());
					tw.addTree(commit.getTree());
					tw.setRecursive(true);
					while (tw.next()) {
						ObjectId pId = tw.getObjectId(0);
						ObjectId cId = tw.getObjectId(1);
						if (!Objects.equals(pId, cId)) {
							paths.add(tw.getPathString());
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.debug("Failed to get touched paths for commit {}", commit.name(), e);
		}
		return paths;
	}

	public synchronized boolean isPathTracked(String relPath) {
		if (!isInitialized()) {
			return false;
		}
		try {
			ObjectId head = repo.resolve(Constants.HEAD);
			if (head == null) {
				return false;
			}
			try (RevWalk walk = new RevWalk(repo)) {
				RevCommit commit = walk.parseCommit(head);
				RevTree tree = commit.getTree();
				try (TreeWalk treeWalk = TreeWalk.forPath(repo, relPath, tree)) {
					return treeWalk != null;
				}
			}
		} catch (Exception e) {
			return false;
		}
	}

	public synchronized boolean recordBaseline(String classType, String smaliCode) {
		if (!ensureInitialized() || classType == null || smaliCode == null) {
			return false;
		}
		// Normalize line endings before storing
		String normalized = smaliCode.replace("\r\n", "\n").replace('\r', '\n');
		String relPath = getExistingTrackedPath(classType);
		if (baselineRecorded.containsKey(relPath) || isPathTracked(relPath)) {
			baselineRecorded.put(relPath, true);
			// Populate cache even if baseline already existed
			baselineSmaliCache.putIfAbsent(classType, normalized);
			return false;
		}
		try {
			writeFile(relPath, normalized);
			git.add().addFilepattern(relPath).call();
			git.commit()
					.setAuthor(IDENT)
					.setCommitter(IDENT)
					.setMessage("[baseline] " + classType)
					.call();
			baselineRecorded.put(relPath, true);
			// Bug 6 fix: cache baseline content so getBaselineSmali() is O(1)
			baselineSmaliCache.put(classType, normalized);
			LOG.info("Recorded baseline for class: {}", classType);
			return true;
		} catch (Exception e) {
			LOG.error("Failed to record baseline for {}", classType, e);
			return false;
		}
	}

	public synchronized boolean hasEditsBeyondBaseline(String classType) {
		if (!isInitialized() || classType == null) {
			return false;
		}
		List<PatchCommit> history = getClassHistory(classType);
		if (history.isEmpty()) {
			return false;
		}
		String baseline = getBaselineSmali(classType);
		String latest = getSmaliAtCommit(classType, history.get(0).getFullHash());
		if (baseline == null || latest == null) {
			return false;
		}
		return !baseline.equals(latest);
	}

	public synchronized PatchCommit recordEdit(String classType, String smaliCode, String commitMsg) {
		if (!ensureInitialized() || classType == null || smaliCode == null) {
			return null;
		}
		// Normalize line endings before any comparison or storage
		String normalized = smaliCode.replace("\r\n", "\n").replace('\r', '\n');
		String relPath = getExistingTrackedPath(classType);
		if (!isPathTracked(relPath)) {
			recordBaseline(classType, normalized);
			return null;
		}
		// Bug 1 fix: deduplicate — skip commit if content is identical to HEAD
		String currentHead = getSmaliAtHead(relPath);
		if (normalized.equals(currentHead)) {
			LOG.debug("Skipping duplicate commit for {} — content unchanged", classType);
			return null;
		}
		try {
			writeFile(relPath, normalized);
			git.add().addFilepattern(relPath).call();
			String msg = (commitMsg != null && !commitMsg.trim().isEmpty())
					? commitMsg
					: "[edit] " + classType;
			RevCommit rev = git.commit()
					.setAuthor(IDENT)
					.setCommitter(IDENT)
					.setMessage(msg)
					.call();
			LOG.info("Committed edit for class {}: [{}] {}", classType, rev.name().substring(0, 7), msg);
			return new PatchCommit(rev.name(), rev.getCommitTime() * 1000L,
					rev.getAuthorIdent().getName(), rev.getFullMessage(), null,
					Collections.singletonList(classType));
		} catch (Exception e) {
			LOG.error("Failed to record edit for {}", classType, e);
			return null;
		}
	}

	/**
	 * Reads the smali content of a file directly from HEAD without walking commit history.
	 * Used for deduplication check in recordEdit.
	 */
	private String getSmaliAtHead(String relPath) {
		try {
			ObjectId head = repo.resolve(Constants.HEAD);
			if (head == null) {
				return null;
			}
			try (RevWalk walk = new RevWalk(repo)) {
				RevCommit commit = walk.parseCommit(head);
				try (TreeWalk treeWalk = TreeWalk.forPath(repo, relPath, commit.getTree())) {
					if (treeWalk != null) {
						ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
						return new String(loader.getBytes(), StandardCharsets.UTF_8)
								.replace("\r\n", "\n").replace('\r', '\n');
					}
				}
			}
		} catch (Exception e) {
			LOG.debug("Could not read HEAD content for {}", relPath, e);
		}
		return null;
	}

	public synchronized PatchCommit recordExportMilestone(String exportInfo, Collection<String> modifiedClasses) {
		if (!ensureInitialized()) {
			return null;
		}
		try {
			String msg = (exportInfo != null && !exportInfo.trim().isEmpty())
					? (exportInfo.startsWith("[export]") ? exportInfo : "[export] " + exportInfo)
					: "[export] Exported Patched APK";

			RevCommit rev = git.commit()
					.setAuthor(IDENT)
					.setCommitter(IDENT)
					.setMessage(msg)
					.setAllowEmpty(true)
					.call();

			String tagName = "export-" + System.currentTimeMillis();
			try {
				git.tag().setName(tagName).setMessage(msg).setTagger(IDENT).call();
			} catch (Exception e) {
				LOG.debug("Tag creation failed for export milestone", e);
			}

			LOG.info("Recorded export milestone: [{}] {}", rev.name().substring(0, 7), msg);
			return new PatchCommit(rev.name(), rev.getCommitTime() * 1000L,
					IDENT.getName(), msg, tagName,
					modifiedClasses != null ? new ArrayList<>(modifiedClasses) : Collections.emptyList());
		} catch (Exception e) {
			LOG.error("Failed to record export milestone", e);
			return null;
		}
	}

	public synchronized PatchCommit recordDeployMilestone(String milestoneName, Collection<String> modifiedClasses) {
		if (!ensureInitialized()) {
			return null;
		}
		try {
			String msg = (milestoneName != null && !milestoneName.trim().isEmpty())
					? (milestoneName.startsWith("[deploy]") ? milestoneName : "[deploy] " + milestoneName)
					: "[deploy] Quick Deploy";

			RevCommit rev = git.commit()
					.setAuthor(IDENT)
					.setCommitter(IDENT)
					.setMessage(msg)
					.setAllowEmpty(true)
					.call();

			String tagName = "deploy-" + System.currentTimeMillis();
			try {
				git.tag().setName(tagName).setMessage(msg).setTagger(IDENT).call();
			} catch (Exception e) {
				LOG.debug("Tag creation failed for deploy milestone", e);
			}

			LOG.info("Recorded deploy milestone: [{}] {}", rev.name().substring(0, 7), msg);
			return new PatchCommit(rev.name(), rev.getCommitTime() * 1000L,
					IDENT.getName(), msg, tagName,
					modifiedClasses != null ? new ArrayList<>(modifiedClasses) : Collections.emptyList());
		} catch (Exception e) {
			LOG.error("Failed to record deploy milestone", e);
			return null;
		}
	}

	public synchronized PatchCommit recordReloadSnapshot(Collection<String> modifiedClasses) {
		if (!ensureInitialized()) {
			return null;
		}
		try {
			String msg = "[snapshot-before-reload] Snapshot before reload";
			RevCommit rev = git.commit()
					.setAuthor(IDENT)
					.setCommitter(IDENT)
					.setMessage(msg)
					.setAllowEmpty(true)
					.call();

			String tagName = "reload-" + System.currentTimeMillis();
			try {
				git.tag().setName(tagName).setMessage(msg).setTagger(IDENT).call();
			} catch (Exception e) {
				LOG.debug("Tag creation failed for reload snapshot", e);
			}

			LOG.info("Created pre-reload snapshot: [{}] {}", rev.name().substring(0, 7), tagName);
			return new PatchCommit(rev.name(), rev.getCommitTime() * 1000L,
					IDENT.getName(), msg, tagName,
					modifiedClasses != null ? new ArrayList<>(modifiedClasses) : Collections.emptyList());
		} catch (Exception e) {
			LOG.error("Failed to record reload snapshot", e);
			return null;
		}
	}

	private Map<String, String> getCommitToTagMap() {
		Map<String, String> map = new HashMap<>();
		try {
			List<Ref> tags = git.tagList().call();
			for (Ref ref : tags) {
				Ref peeled = repo.getRefDatabase().peel(ref);
				ObjectId targetId = peeled.getPeeledObjectId();
				if (targetId == null) {
					targetId = ref.getObjectId();
				}
				if (targetId != null) {
					String name = ref.getName();
					if (name.startsWith("refs/tags/")) {
						name = name.substring("refs/tags/".length());
					}
					map.put(targetId.name(), name);
				}
			}
		} catch (Exception e) {
			LOG.debug("Failed to list tags", e);
		}
		return map;
	}

	private boolean isMilestoneCommit(RevCommit rev, @Nullable String tagName) {
		String msg = rev.getFullMessage();
		if (msg.startsWith("[export]") || msg.startsWith("[deploy]") || msg.startsWith("[snapshot]")) {
			return true;
		}
		if (tagName != null && (tagName.startsWith("export-") || tagName.startsWith("deploy-") || tagName.startsWith("reload-"))) {
			return true;
		}
		return false;
	}

	private boolean isFileInCommitTree(RevCommit commit, String relPath) {
		try (TreeWalk treeWalk = TreeWalk.forPath(repo, relPath, commit.getTree())) {
			return treeWalk != null;
		} catch (Exception e) {
			return false;
		}
	}

	private boolean isPathModifiedInCommit(RevCommit rev, String relPath) {
		try {
			if (rev.getParentCount() == 0) {
				return isFileInCommitTree(rev, relPath);
			}
			ObjectId idCurrent = null;
			try (TreeWalk tw = TreeWalk.forPath(repo, relPath, rev.getTree())) {
				if (tw != null) {
					idCurrent = tw.getObjectId(0);
				}
			}
			ObjectId idParent = null;
			try (TreeWalk tw = TreeWalk.forPath(repo, relPath, rev.getParent(0).getTree())) {
				if (tw != null) {
					idParent = tw.getObjectId(0);
				}
			}
			if (idCurrent == null && idParent == null) {
				return false;
			}
			return !Objects.equals(idCurrent, idParent);
		} catch (Exception e) {
			return false;
		}
	}

	public synchronized List<PatchCommit> getClassHistory(String classType) {
		if (!isInitialized() || classType == null) {
			return Collections.emptyList();
		}
		try {
			ObjectId head = repo.resolve(Constants.HEAD);
			if (head == null) {
				return Collections.emptyList();
			}
		} catch (Exception e) {
			return Collections.emptyList();
		}

		List<String> candidatePaths = getPossibleFilePaths(classType);
		List<PatchCommit> history = new ArrayList<>();
		try {
			Map<String, String> commitTags = getCommitToTagMap();
			Iterable<RevCommit> commits = git.log().call();
			for (RevCommit rev : commits) {
				String tagName = commitTags.get(rev.name());

				boolean isClassEdit = false;
				boolean isMilestone = false;
				for (String path : candidatePaths) {
					if (isPathModifiedInCommit(rev, path)) {
						isClassEdit = true;
						break;
					}
					if (isMilestoneCommit(rev, tagName) && isFileInCommitTree(rev, path)) {
						isMilestone = true;
						break;
					}
				}

				if (isClassEdit || isMilestone) {
					history.add(new PatchCommit(rev.name(), rev.getCommitTime() * 1000L,
							rev.getAuthorIdent().getName(), rev.getFullMessage(), tagName,
							Collections.singletonList(classType)));
				}
			}
			return history;
		} catch (Exception e) {
			LOG.error("Failed to get class history for {}", classType, e);
			return Collections.emptyList();
		}
	}

	public synchronized String getHistoricalSmali(String classType, int stepsBack) {
		if (!isInitialized() || classType == null) {
			return null;
		}
		List<PatchCommit> history = getClassHistory(classType);
		if (history.isEmpty()) {
			return null;
		}
		if (stepsBack < 0 || stepsBack >= history.size()) {
			return null;
		}
		String commitHash = history.get(stepsBack).getFullHash();
		return getSmaliAtCommit(classType, commitHash);
	}

	/**
	 * Bug 7 fix: Finds the most recent historical smali that is content-different from
	 * {@code currentCode}. This avoids returning a duplicate commit that looks identical
	 * to the current state — which would make rollback and diff appear to do nothing.
	 *
	 * @param classType   the fully-qualified class name
	 * @param currentCode the LF-normalized current smali content
	 * @return the most recent distinct prior smali, or null if no distinct history exists
	 */
	public synchronized String getPreviousDistinctSmali(String classType, String currentCode) {
		if (!isInitialized() || classType == null) {
			return null;
		}
		List<PatchCommit> history = getClassHistory(classType);
		// history is ordered newest-first; index 0 = HEAD
		for (int i = 1; i < history.size(); i++) {
			String candidate = getSmaliAtCommit(classType, history.get(i).getFullHash());
			if (candidate != null && !candidate.equals(currentCode)) {
				return candidate;
			}
		}
		return null;
	}


	public synchronized String getBaselineSmali(String classType) {
		if (!isInitialized() || classType == null) {
			return null;
		}
		// Bug 6 fix: return from O(1) cache if available — avoids full git log walk on every Ctrl+S
		String cached = baselineSmaliCache.get(classType);
		if (cached != null) {
			return cached;
		}
		// Fallback: walk history to find baseline commit (first time only)
		List<PatchCommit> history = getClassHistory(classType);
		if (history.isEmpty()) {
			return null;
		}
		String result = null;
		for (int i = history.size() - 1; i >= 0; i--) {
			if (history.get(i).isBaseline()) {
				result = getSmaliAtCommit(classType, history.get(i).getFullHash());
				break;
			}
		}
		if (result == null && !history.isEmpty()) {
			result = getSmaliAtCommit(classType, history.get(history.size() - 1).getFullHash());
		}
		if (result != null) {
			baselineSmaliCache.put(classType, result);
		}
		return result;
	}

	public synchronized String getSmaliAtCommit(String classType, String commitHash) {
		if (!isInitialized() || classType == null || commitHash == null) {
			return null;
		}
		List<String> candidatePaths = getPossibleFilePaths(classType);
		try {
			ObjectId commitId = repo.resolve(commitHash);
			if (commitId == null) {
				return null;
			}
			try (RevWalk walk = new RevWalk(repo)) {
				RevCommit commit = walk.parseCommit(commitId);
				RevTree tree = commit.getTree();
				for (String relPath : candidatePaths) {
					try (TreeWalk treeWalk = TreeWalk.forPath(repo, relPath, tree)) {
						if (treeWalk != null) {
							ObjectId blobId = treeWalk.getObjectId(0);
							ObjectLoader loader = repo.open(blobId);
							String content = new String(loader.getBytes(), StandardCharsets.UTF_8);
							return content.replace("\r\n", "\n").replace('\r', '\n');
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.error("Failed to read smali for {} at {}", classType, commitHash, e);
		}
		return null;
	}

	public synchronized String getDiff(String classType, String oldCommitHash, String newCommitHash) {
		if (!isInitialized() || classType == null) {
			return "";
		}
		String relPath = getExistingTrackedPath(classType);
		try {
			ObjectId oldId = (oldCommitHash != null) ? repo.resolve(oldCommitHash + "^{tree}") : null;
			ObjectId newId = (newCommitHash != null) ? repo.resolve(newCommitHash + "^{tree}") : null;

			if (oldId == null || newId == null) {
				List<PatchCommit> history = getClassHistory(classType);
				if (history.size() < 2) {
					return "";
				}
				if (oldId == null) {
					oldId = repo.resolve(history.get(1).getFullHash() + "^{tree}");
				}
				if (newId == null) {
					newId = repo.resolve(history.get(0).getFullHash() + "^{tree}");
				}
			}

			CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
			try (ObjectReader reader = repo.newObjectReader()) {
				oldTreeIter.reset(reader, oldId);
			}

			CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
			try (ObjectReader reader = repo.newObjectReader()) {
				newTreeIter.reset(reader, newId);
			}

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (DiffFormatter df = new DiffFormatter(out)) {
				df.setRepository(repo);
				df.setPathFilter(PathFilter.create(relPath));
				df.setDiffComparator(RawTextComparator.DEFAULT);
				df.setDetectRenames(false);
				List<DiffEntry> diffs = df.scan(oldTreeIter, newTreeIter);
				for (DiffEntry diff : diffs) {
					df.format(diff);
				}
			}
			return out.toString(StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOG.error("Failed to compute diff for {}", classType, e);
			return "";
		}
	}

	public synchronized void clearHistory() {
		close();
		if (workingDir != null && Files.exists(workingDir)) {
			try {
				deleteRecursively(workingDir.toFile());
			} catch (Exception e) {
				LOG.warn("Failed to delete patch history dir: {}", workingDir, e);
			}
		}
		workingDir = null;
		initialized = false;
		baselineRecorded.clear();
		baselineSmaliCache.clear();
	}

	public synchronized void close() {
		if (git != null) {
			git.close();
			git = null;
		}
		if (repo != null) {
			repo.close();
			repo = null;
		}
		initialized = false;
	}

	private void writeFile(String relPath, String content) throws IOException {
		Path target = workingDir.resolve(relPath);
		if (target.getParent() != null) {
			Files.createDirectories(target.getParent());
		}
		String normalized = content != null ? content.replace("\r\n", "\n").replace('\r', '\n') : "";
		Files.write(target, normalized.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Returns a map of (relPath -> latestSmaliContent) for all smali files tracked in HEAD.
	 */
	public synchronized Map<String, String> getAllTrackedSmaliFiles() {
		if (!isInitialized()) {
			return Collections.emptyMap();
		}
		Map<String, String> result = new HashMap<>();
		try {
			ObjectId head = repo.resolve(Constants.HEAD);
			if (head == null) {
				return Collections.emptyMap();
			}
			try (RevWalk walk = new RevWalk(repo)) {
				RevCommit commit = walk.parseCommit(head);
				RevTree tree = commit.getTree();
				try (TreeWalk treeWalk = new TreeWalk(repo)) {
					treeWalk.addTree(tree);
					treeWalk.setRecursive(true);
					while (treeWalk.next()) {
						String pathString = treeWalk.getPathString();
						if (pathString.endsWith(".smali")) {
							ObjectId blobId = treeWalk.getObjectId(0);
							ObjectLoader loader = repo.open(blobId);
							String content = new String(loader.getBytes(), StandardCharsets.UTF_8);
							result.put(pathString, content.replace("\r\n", "\n").replace('\r', '\n'));
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.error("Failed to retrieve tracked smali files", e);
		}
		return result;
	}

	private void copyDirectoryRecursively(Path source, Path target) throws IOException {
		if (Files.isDirectory(source)) {
			if (!Files.exists(target)) {
				Files.createDirectories(target);
			}
			try (java.util.stream.Stream<Path> stream = Files.list(source)) {
				for (Path child : (Iterable<Path>) stream::iterator) {
					copyDirectoryRecursively(child, target.resolve(child.getFileName()));
				}
			}
		} else {
			Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		file.delete();
	}
}
