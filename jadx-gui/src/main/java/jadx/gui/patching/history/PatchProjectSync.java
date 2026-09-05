package jadx.gui.patching.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.input.ICodeLoader;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.gui.JadxWrapper;
import jadx.gui.device.debugger.DbgUtils;
import jadx.gui.patching.ModifiedDexManager;
import jadx.gui.settings.JadxProject;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.UiUtils;
import jadx.plugins.input.dex.DexInputPlugin;
import jadx.plugins.input.smali.SmaliUtils;

/**
 * Manages seamless project-level persistence and restoration of patch history
 * and modified class bytecode alongside JADX project files (.jadx).
 * <p>
 * Ensures zero Git noise for the user and 100% upstream rebase compatibility.
 */
public final class PatchProjectSync {
	private static final Logger LOG = LoggerFactory.getLogger(PatchProjectSync.class);
	public static final String HISTORY_DIR_SUFFIX = ".history";

	private PatchProjectSync() {
	}

	/**
	 * Resolves the history directory path corresponding to a given .jadx project path.
	 * E.g. "path/to/app.jadx" -> "path/to/app.jadx.history"
	 */
	public static Path getHistoryDir(Path projectPath) {
		if (projectPath == null) {
			return null;
		}
		return projectPath.resolveSibling(projectPath.getFileName().toString() + HISTORY_DIR_SUFFIX);
	}

	/**
	 * Called when a project is saved (project.save() or project.saveAs()).
	 * Silently migrates or attaches the Git patch repository to &lt;project&gt;.jadx.history.
	 */
	public static void onProjectSave(JadxProject project, Path savePath) {
		if (savePath == null) {
			return;
		}
		try {
			Path historyDir = getHistoryDir(savePath);
			PatchHistoryManager historyManager = PatchHistoryManager.getInstance();

			// Only migrate if we have an active history manager with commits or modifications
			if (historyManager.isInitialized()) {
				LOG.info("Syncing patch history to project directory: {}", historyDir);
				historyManager.migrateTo(historyDir);
			}
		} catch (Exception e) {
			LOG.error("Failed to sync patch history on project save: {}", savePath, e);
		}
	}

	/**
	 * Called after a project finishes loading in MainWindow (MainWindow.onOpen).
	 * If a &lt;project&gt;.jadx.history directory exists, silently attaches to it and
	 * restores all modified classes into the decompilation context.
	 */
	public static void onProjectLoaded(MainWindow mainWindow, JadxProject project) {
		Path projectPath = project != null ? project.getProjectPath() : null;
		if (projectPath == null) {
			return;
		}
		Path historyDir = getHistoryDir(projectPath);
		if (!Files.exists(historyDir) || !Files.isDirectory(historyDir)) {
			LOG.debug("No patch history found for project at: {}", historyDir);
			return;
		}

		try {
			LOG.info("Found persistent patch history at: {}, restoring patches...", historyDir);
			PatchHistoryManager historyManager = PatchHistoryManager.getInstance();
			boolean initialized = historyManager.init(historyDir);
			if (!initialized) {
				LOG.warn("Failed to initialize patch history from: {}", historyDir);
				return;
			}

			// Restore all modified classes from the repository
			restorePatches(mainWindow);
		} catch (Exception e) {
			LOG.error("Failed to restore project patch history from: {}", historyDir, e);
		}
	}

	/**
	 * Re-assembles and re-injects all tracked smali classes that differ from baseline.
	 */
	private static void restorePatches(MainWindow mainWindow) {
		JadxWrapper wrapper = mainWindow.getWrapper();
		if (wrapper == null) {
			return;
		}
		RootNode rootNode = wrapper.getRootNode();
		if (rootNode == null) {
			return;
		}

		PatchHistoryManager historyManager = PatchHistoryManager.getInstance();
		Map<String, String> trackedSmaliFiles = historyManager.getAllTrackedSmaliFiles();
		if (trackedSmaliFiles.isEmpty()) {
			return;
		}

		List<ClassNode> reloadedTopClasses = new ArrayList<>();
		int restoredCount = 0;

		for (Map.Entry<String, String> entry : trackedSmaliFiles.entrySet()) {
			String relPath = entry.getKey(); // e.g. classes/com/example/MainActivity.smali
			String smaliCode = entry.getValue();

			// Convert smali path to class type
			String classType = pathToClassType(relPath);
			if (classType == null) {
				continue;
			}

			// Check if this class has modifications beyond baseline
			if (!historyManager.hasEditsBeyondBaseline(classType)) {
				continue;
			}

			try {
				byte[] dexBytes = SmaliUtils.assemble(smaliCode);
				if (dexBytes == null || dexBytes.length == 0) {
					continue;
				}

				ClassNode targetClassNode = rootNode.resolveClass(ArgType.object(classType));
				String targetDexName = targetClassNode != null ? targetClassNode.getInputFileName() : "classes.dex";
				if (targetDexName == null || targetDexName.equals("memory.dex") || targetDexName.isEmpty()) {
					targetDexName = "classes.dex";
				}

				DexInputPlugin dexInput = new DexInputPlugin();
				ICodeLoader codeLoader = dexInput.loadDex(dexBytes, targetDexName);
				String finalTargetDexName = targetDexName;

				codeLoader.visitClasses(newClsData -> {
					String rawType = newClsData.getType();
					ClassNode clsNode = rootNode.resolveClass(ArgType.object(rawType));
					if (clsNode != null) {
						String origDex = clsNode.getInputFileName();
						if (origDex == null || origDex.equals("memory.dex") || origDex.isEmpty()) {
							origDex = finalTargetDexName;
						}
						clsNode.updateClassData(newClsData);
						clsNode.setInputFileName(origDex);
						ModifiedDexManager.getInstance().registerModifiedClass(origDex, rawType, dexBytes);
						ClassNode topParent = clsNode.getTopParentClass();
						if (!reloadedTopClasses.contains(topParent)) {
							reloadedTopClasses.add(topParent);
						}
					}
				});

				restoredCount++;
			} catch (Exception e) {
				LOG.error("Failed to restore patched class: {}", classType, e);
			}
		}

		for (ClassNode topCls : reloadedTopClasses) {
			DbgUtils.clearSmaliCache(topCls.getClassInfo());
			if (topCls.getJavaNode() != null) {
				topCls.getJavaNode().reload();
			}
		}

		if (restoredCount > 0) {
			LOG.info("Successfully restored {} patched class(es) from project history", restoredCount);
			final int count = restoredCount;
			UiUtils.uiRun(() -> UiUtils.showToast(mainWindow, "✓ Restored " + count + " patched classes from project history"));
		}
	}

	/**
	 * Converts relative smali file path back to type descriptor.
	 * E.g., "classes/com/example/MainActivity.smali" -> "Lcom/example/MainActivity;"
	 */
	public static String pathToClassType(String relPath) {
		if (relPath == null || !relPath.endsWith(".smali")) {
			return null;
		}
		String clean = relPath.substring(0, relPath.length() - ".smali".length());
		if (clean.startsWith("classes/")) {
			clean = clean.substring("classes/".length());
		}
		clean = clean.replace('\\', '/');
		return "L" + clean + ";";
	}
}
