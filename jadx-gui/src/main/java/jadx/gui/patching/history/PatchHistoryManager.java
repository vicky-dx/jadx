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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

	public synchronized PatchCommit recordDeployMilestone(String milestoneName, Collection<String> modifiedClasses) {
		if (!ensureInitialized()) {
			return null;
		}
		try {
			ObjectId head = repo.resolve(Constants.HEAD);
			if (head == null) {
				return null;
			}
			String tagName = "deploy-" + System.currentTimeMillis();
			Ref ref = git.tag()
					.setName(tagName)
					.setMessage(milestoneName != null ? milestoneName : "Deploy milestone")
					.setTagger(IDENT)
					.call();
			LOG.info("Created deploy milestone tag: {}", tagName);
			return new PatchCommit(head.name(), System.currentTimeMillis(),
					IDENT.getName(), milestoneName, tagName,
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
			ObjectId head = repo.resolve(Constants.HEAD);
			if (head == null) {
				return null;
			}
			String tagName = "reload-" + System.currentTimeMillis();
			git.tag()
					.setName(tagName)
					.setMessage("Snapshot before reload")
					.setTagger(IDENT)
					.call();
			LOG.info("Created pre-reload snapshot tag: {}", tagName);
			return new PatchCommit(head.name(), System.currentTimeMillis(),
					IDENT.getName(), "Snapshot before reload", tagName,
					modifiedClasses != null ? new ArrayList<>(modifiedClasses) : Collections.emptyList());
		} catch (Exception e) {
			LOG.error("Failed to record reload snapshot", e);
			return null;
		}
	}

	public synchronized List<PatchCommit> getClassHistory(String classType) {
		if (!isInitialized() || classType == null) {
			return Collections.emptyList();
		}
		String relPath = classTypeToFilePath(classType);
		List<PatchCommit> history = new ArrayList<>();
		try {
			Iterable<RevCommit> commits = git.log().addPath(relPath).call();
			for (RevCommit rev : commits) {
				history.add(new PatchCommit(rev.name(), rev.getCommitTime() * 1000L,
						rev.getAuthorIdent().getName(), rev.getFullMessage(), null,
						Collections.singletonList(classType)));
			}
		} catch (Exception e) {
			LOG.error("Failed to get class history for {}", classType, e);
		}
		return history;
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
