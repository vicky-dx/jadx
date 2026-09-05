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

	public synchronized boolean init(Path baseDir) {
		try {
			close();
			if (baseDir == null) {
				workingDir = Files.createTempDirectory("jadx-patch-history-");
				workingDir.toFile().deleteOnExit();
			} else {
				workingDir = baseDir.resolve(".jadx-patch-history");
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
			LOG.info("Initialized Git patch history at: {}", workingDir);
			return true;
		} catch (Exception e) {
			LOG.error("Failed to initialize Git patch history", e);
			initialized = false;
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
		clean = clean.replace('.', '/').replace('$', '_');
		return "classes/" + clean + ".smali";
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
		String relPath = classTypeToFilePath(classType);
		if (baselineRecorded.containsKey(relPath) || isPathTracked(relPath)) {
			baselineRecorded.put(relPath, true);
			return false;
		}
		try {
			writeFile(relPath, smaliCode);
			git.add().addFilepattern(relPath).call();
			git.commit()
					.setAuthor(IDENT)
					.setCommitter(IDENT)
					.setMessage("[baseline] " + classType)
					.call();
			baselineRecorded.put(relPath, true);
			LOG.info("Recorded baseline for class: {}", classType);
			return true;
		} catch (Exception e) {
			LOG.error("Failed to record baseline for {}", classType, e);
			return false;
		}
	}

	public synchronized PatchCommit recordEdit(String classType, String smaliCode, String commitMsg) {
		if (!ensureInitialized() || classType == null || smaliCode == null) {
			return null;
		}
		String relPath = classTypeToFilePath(classType);
		if (!isPathTracked(relPath)) {
			recordBaseline(classType, smaliCode);
			return null;
		}
		try {
			writeFile(relPath, smaliCode);
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

		String relPath = classTypeToFilePath(classType);
		List<PatchCommit> history = new ArrayList<>();
		try {
			Map<String, String> commitTags = getCommitToTagMap();
			Iterable<RevCommit> commits = git.log().call();
			for (RevCommit rev : commits) {
				String tagName = commitTags.get(rev.name());
				String msg = rev.getFullMessage();

				boolean isClassEdit = msg.contains(classType) || isPathModifiedInCommit(rev, relPath);
				boolean isMilestone = isMilestoneCommit(rev, tagName) && isFileInCommitTree(rev, relPath);

				if (isClassEdit || isMilestone) {
					history.add(new PatchCommit(rev.name(), rev.getCommitTime() * 1000L,
							rev.getAuthorIdent().getName(), msg, tagName,
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

	public synchronized String getBaselineSmali(String classType) {
		if (!isInitialized() || classType == null) {
			return null;
		}
		List<PatchCommit> history = getClassHistory(classType);
		if (history.isEmpty()) {
			return null;
		}
		for (int i = history.size() - 1; i >= 0; i--) {
			if (history.get(i).isBaseline()) {
				return getSmaliAtCommit(classType, history.get(i).getFullHash());
			}
		}
		String baselineCommitHash = history.get(history.size() - 1).getFullHash();
		return getSmaliAtCommit(classType, baselineCommitHash);
	}

	public synchronized String getSmaliAtCommit(String classType, String commitHash) {
		if (!isInitialized() || classType == null || commitHash == null) {
			return null;
		}
		String relPath = classTypeToFilePath(classType);
		try {
			ObjectId commitId = repo.resolve(commitHash);
			if (commitId == null) {
				return null;
			}
			try (RevWalk walk = new RevWalk(repo)) {
				RevCommit commit = walk.parseCommit(commitId);
				RevTree tree = commit.getTree();
				try (TreeWalk treeWalk = TreeWalk.forPath(repo, relPath, tree)) {
					if (treeWalk != null) {
						ObjectId blobId = treeWalk.getObjectId(0);
						ObjectLoader loader = repo.open(blobId);
						String content = new String(loader.getBytes(), StandardCharsets.UTF_8);
						return content.replace("\r\n", "\n").replace('\r', '\n');
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
		String relPath = classTypeToFilePath(classType);
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
