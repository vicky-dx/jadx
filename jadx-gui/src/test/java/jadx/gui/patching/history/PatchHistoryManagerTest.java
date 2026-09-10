package jadx.gui.patching.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatchHistoryManagerTest {

	private Path tempDir;
	private PatchHistoryManager historyManager;

	@BeforeEach
	void setUp() throws Exception {
		tempDir = Files.createTempDirectory("jadx-test-history-");
		historyManager = PatchHistoryManager.getInstance();
		historyManager.init(tempDir);
	}

	@AfterEach
	void tearDown() {
		historyManager.clearHistory();
	}

	@Test
	void testInitializationAndGitLifecycle() {
		assertThat(historyManager.isInitialized()).isTrue();
		assertThat(historyManager.getWorkingDir()).isNotNull();
		assertThat(Files.exists(historyManager.getWorkingDir().resolve(".git"))).isTrue();
	}

	@Test
	void testBaselineCommitCreation() {
		String classType = "Lcom/example/AuthService;";
		String originalSmali = ".class public Lcom/example/AuthService;\n"
				+ ".super Ljava/lang/Object;\n\n"
				+ ".method public isLicensed()Z\n"
				+ "    .registers 2\n"
				+ "    const/4 v0, 0x0\n"
				+ "    return v0\n"
				+ ".end method\n";

		boolean firstRecorded = historyManager.recordBaseline(classType, originalSmali);
		assertThat(firstRecorded).isTrue();

		// Idempotent: recording again should return false (not duplicate)
		boolean secondRecorded = historyManager.recordBaseline(classType, originalSmali);
		assertThat(secondRecorded).isFalse();

		List<PatchCommit> history = historyManager.getClassHistory(classType);
		assertThat(history).hasSize(1);
		assertThat(history.get(0).isBaseline()).isTrue();
		assertThat(history.get(0).getMessage()).contains("[baseline]");
	}

	@Test
	void testSequentialEditsAndMultiFileIsolation() {
		String file1 = "Lcom/example/File1;";
		String file2 = "Lcom/example/File2;";

		// File 1: baseline -> edit 1 -> edit 2 -> edit 3
		historyManager.recordBaseline(file1, "file1_base");
		historyManager.recordEdit(file1, "file1_edit1", "Edit 1 in File1");
		historyManager.recordEdit(file1, "file1_edit2", "Edit 2 in File1");
		historyManager.recordEdit(file1, "file1_edit3", "Edit 3 in File1");

		// File 2: baseline -> edit 4 -> edit 5 -> edit 6
		historyManager.recordBaseline(file2, "file2_base");
		historyManager.recordEdit(file2, "file2_edit4", "Edit 4 in File2");
		historyManager.recordEdit(file2, "file2_edit5", "Edit 5 in File2");
		historyManager.recordEdit(file2, "file2_edit6", "Edit 6 in File2");

		// Assert histories are tracked independently
		assertThat(historyManager.getClassHistory(file1)).hasSize(4);
		assertThat(historyManager.getClassHistory(file2)).hasSize(4);

		// Rollback File2 by 1 step (from edit6 back to edit5)
		String file2RolledBack = historyManager.getHistoricalSmali(file2, 1);
		assertThat(file2RolledBack).isEqualTo("file2_edit5");

		// Assert File1 is completely isolated and untouched at edit3
		String file1Current = historyManager.getHistoricalSmali(file1, 0);
		assertThat(file1Current).isEqualTo("file1_edit3");
	}

	@Test
	void testRevertToBaseline() {
		String classType = "Lcom/example/TargetClass;";
		String baselineCode = ".method public test()V\nreturn-void\n.end method\n";

		historyManager.recordBaseline(classType, baselineCode);
		for (int i = 1; i <= 5; i++) {
			historyManager.recordEdit(classType, baselineCode + "# destructive edit " + i, "Edit " + i);
		}

		assertThat(historyManager.getClassHistory(classType)).hasSize(6);

		String restoredBaseline = historyManager.getBaselineSmali(classType);
		assertThat(restoredBaseline).isEqualTo(baselineCode);
	}

	@Test
	void testVisualDiffEngine() {
		String classType = "Lcom/example/LicenseChecker;";
		String v1 = ".method public check()Z\nconst/4 v0, 0x0\nreturn v0\n.end method\n";
		String v2 = ".method public check()Z\nconst/4 v0, 0x1\nreturn v0\n.end method\n";

		historyManager.recordBaseline(classType, v1);
		historyManager.recordEdit(classType, v2, "Patched license to return true");

		String diff = historyManager.getDiff(classType, null, null);
		assertThat(diff).isNotEmpty();
		assertThat(diff).contains("-const/4 v0, 0x0");
		assertThat(diff).contains("+const/4 v0, 0x1");
	}

	@Test
	void testDeployAndExportMilestonesInHistory() {
		String classType = "Lcom/example/PaymentGate;";
		historyManager.recordBaseline(classType, "payment_base");
		historyManager.recordEdit(classType, "payment_patched", "Bypass payment check");

		PatchCommit exportMilestone = historyManager.recordExportMilestone("Exported Patched APK: app.apk", Arrays.asList(classType));
		assertThat(exportMilestone).isNotNull();
		assertThat(exportMilestone.isExportMilestone()).isTrue();

		PatchCommit deployMilestone = historyManager.recordDeployMilestone("Deploy #1 - Samsung", Arrays.asList(classType));
		assertThat(deployMilestone).isNotNull();
		assertThat(deployMilestone.getTagName()).startsWith("deploy-");
		assertThat(deployMilestone.isDeployMilestone()).isTrue();

		List<PatchCommit> history = historyManager.getClassHistory(classType);
		assertThat(history).hasSize(4);
		assertThat(history.get(0).isDeployMilestone()).isTrue();
		assertThat(history.get(1).isExportMilestone()).isTrue();
		assertThat(history.get(2).getMessage()).isEqualTo("Bypass payment check");
		assertThat(history.get(3).isBaseline()).isTrue();

		// Check smali can be retrieved for all checkpoints
		assertThat(historyManager.getSmaliAtCommit(classType, history.get(0).getFullHash())).isEqualTo("payment_patched");
		assertThat(historyManager.getSmaliAtCommit(classType, history.get(1).getFullHash())).isEqualTo("payment_patched");
		assertThat(historyManager.getSmaliAtCommit(classType, history.get(3).getFullHash())).isEqualTo("payment_base");
	}

	@Test
	void testAccidentalReloadSafety() {
		String classType = "Lcom/example/SessionManager;";
		historyManager.recordBaseline(classType, "session_base");
		historyManager.recordEdit(classType, "session_modified_v1", "Modify session timeout");

		// User triggers Reload (F5)
		PatchCommit snapshot = historyManager.recordReloadSnapshot(Arrays.asList(classType));
		assertThat(snapshot).isNotNull();
		assertThat(snapshot.isReloadSnapshot()).isTrue();

		// Even after simulate reload, historical code can be retrieved
		String retrievedCode = historyManager.getHistoricalSmali(classType, 0);
		assertThat(retrievedCode).isEqualTo("session_modified_v1");
	}

	@Test
	void testSparseStorageEfficiency() throws Exception {
		// Only 2 classes tracked out of hypothetical 10,000
		historyManager.recordBaseline("Lcom/example/C1;", "class1");
		historyManager.recordBaseline("Lcom/example/C2;", "class2");
		historyManager.recordEdit("Lcom/example/C1;", "class1_v2", "update");

		Path gitDir = historyManager.getWorkingDir().resolve(".git");
		assertThat(Files.exists(gitDir)).isTrue();

		// Repository size check: should be minimal (< 100 KB)
		long totalSize = Files.walk(gitDir)
				.filter(p -> Files.isRegularFile(p))
				.mapToLong(p -> {
					try {
						return Files.size(p);
					} catch (Exception e) {
						return 0L;
					}
				}).sum();

		assertThat(totalSize).isLessThan(100_000L); // under 100 KB!
	}

	@Test
	void testRepositoryMigrationToPersistentProjectDir() throws Exception {
		// Initialize with null to simulate regular unsaved temporary session
		historyManager.init(null);

		Path targetProjectHistoryDir = Files.createTempDirectory("jadx-persisted-project.jadx.history");
		try {
			String classType = "Lcom/example/PersistedClass;";
			historyManager.recordBaseline(classType, "baseline_content");
			historyManager.recordEdit(classType, "edit_v1_content", "First modification");

			assertThat(historyManager.isPersistent()).isFalse();

			// Migrate to project directory
			boolean migrated = historyManager.migrateTo(targetProjectHistoryDir);
			assertThat(migrated).isTrue();
			assertThat(historyManager.isPersistent()).isTrue();
			assertThat(historyManager.getWorkingDir().toAbsolutePath())
					.isEqualTo(targetProjectHistoryDir.toAbsolutePath());

			// Verify history and content are preserved after migration
			List<PatchCommit> history = historyManager.getClassHistory(classType);
			assertThat(history).hasSize(2);
			assertThat(historyManager.getHistoricalSmali(classType, 0)).isEqualTo("edit_v1_content");
			assertThat(historyManager.hasEditsBeyondBaseline(classType)).isTrue();

			// Add another edit in persistent location
			historyManager.recordEdit(classType, "edit_v2_content", "Second modification");
			assertThat(historyManager.getClassHistory(classType)).hasSize(3);
			assertThat(historyManager.getHistoricalSmali(classType, 0)).isEqualTo("edit_v2_content");
		} finally {
			// cleanup target dir
			if (Files.exists(targetProjectHistoryDir)) {
				for (Path p : (Iterable<Path>) Files.walk(targetProjectHistoryDir).sorted((a, b) -> b.compareTo(a))::iterator) {
					try {
						Files.deleteIfExists(p);
					} catch (Exception ignored) {
					}
				}
			}
		}
	}

	@Test
	void testPathToClassTypeMapping() {
		assertThat(PatchProjectSync.pathToClassType("classes/com/example/MainActivity.smali"))
				.isEqualTo("Lcom/example/MainActivity;");
		assertThat(PatchProjectSync.pathToClassType("classes/org/jadx/ui/App.smali"))
				.isEqualTo("Lorg/jadx/ui/App;");
		assertThat(PatchProjectSync.pathToClassType("classes/Main.smali"))
				.isEqualTo("LMain;");
	}

	@Test
	void testDefaultPackageUnification() {
		String smali = ".class public Lzl0;\n.super Ljava/lang/Object;\n";
		byte[] dex = jadx.plugins.input.smali.SmaliUtils.assemble(smali);

		// Register in-memory modification as Lzl0; in classes2.dex
		jadx.gui.patching.ModifiedDexManager.getInstance().clear();
		jadx.gui.patching.ModifiedDexManager.getInstance().registerModifiedClass(
				"classes2.dex", "Lzl0;", dex);

		// Record git history under alias defpackage.zl0
		historyManager.recordBaseline("defpackage.zl0", smali);
		historyManager.recordEdit("defpackage.zl0", smali + "# edit 1\n", "Modified defpackage.zl0");

		// Both "zl0" and "defpackage.zl0" must retrieve the same commit history
		List<PatchCommit> histByRaw = historyManager.getClassHistory("zl0");
		List<PatchCommit> histByAlias = historyManager.getClassHistory("defpackage.zl0");
		assertThat(histByRaw).hasSize(2);
		assertThat(histByAlias).hasSize(2);

		// Smali content lookup must work with either name
		String smaliByRaw = historyManager.getHistoricalSmali("zl0", 0);
		String smaliByAlias = historyManager.getHistoricalSmali("defpackage.zl0", 0);
		assertThat(smaliByRaw).contains("# edit 1");
		assertThat(smaliByAlias).isEqualTo(smaliByRaw);

		// getModifiedClassesOverview must merge in-memory (classes2.dex) and git history into ONE single class summary
		java.util.Map<String, PatchHistoryManager.ModifiedClassSummary> overview = historyManager.getModifiedClassesOverview();
		assertThat(overview).hasSize(1);

		PatchHistoryManager.ModifiedClassSummary summary = overview.values().iterator().next();
		assertThat(summary.isInMemoryModified()).isTrue();
		assertThat(summary.getDexName()).isEqualTo("classes2.dex");
		assertThat(summary.getCheckpointCount()).isEqualTo(2);

		jadx.gui.patching.ModifiedDexManager.getInstance().clear();
	}
}
