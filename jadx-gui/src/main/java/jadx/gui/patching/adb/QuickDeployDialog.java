package jadx.gui.patching.adb;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.android.apksig.ApkSigner;

import jadx.gui.patching.ApkSignerHelper;
import jadx.gui.patching.ModifiedClass;
import jadx.gui.patching.ModifiedDexManager;
import jadx.gui.patching.PackageTypeDetector;
import jadx.gui.patching.PackageTypeDetector.PackageType;
import jadx.gui.patching.history.PatchHistoryManager;
import jadx.gui.patching.ZipAligner;
import jadx.gui.patching.merger.ApkEditorBackend;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.dialog.CommonDialog;

/**
 * Quick Deploy dialog (Ctrl+Shift+R).
 * <p>
 * Pipeline: select device → Patch → ZipAlign → Sign → ADB install → Launch.
 * <p>
 * For XAPK / APKS bundles, automatically merges to a universal APK via
 * {@link ApkEditorBackend}. No user choice needed — speed is the goal.
 * Uses a temporary artifact that is cleaned up after successful installation.
 */
public class QuickDeployDialog extends CommonDialog {

	private static final Logger LOG = LoggerFactory.getLogger(QuickDeployDialog.class);
	private static final long serialVersionUID = 1L;

	private final JComboBox<String> deviceCombo = new JComboBox<>();
	private final JLabel statusLabel = new JLabel("Select device and click Deploy.");
	private final JProgressBar progressBar = new JProgressBar();
	private final JButton deployBtn = new JButton("Deploy");
	private final JButton refreshBtn = new JButton("Refresh Devices");
	private final JButton cancelBtn = new JButton("Cancel");

	private List<AdbDeployer.Device> devices = Collections.emptyList();
	private final AdbDeployer deployer;

	public QuickDeployDialog(MainWindow mainWindow) {
		super(mainWindow);
		String configuredAdb = mainWindow.getSettings().getAdbDialogPath();
		this.deployer = new AdbDeployer(AdbDeployer.resolveAdb(configuredAdb));
		initUI();
		refreshDevices();
	}

	private void initUI() {
		setTitle("Quick Deploy — Ctrl+Shift+R");

		JPanel main = new JPanel(new GridBagLayout());
		main.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridwidth = 1;
		int row = 0;

		// Device selector
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		main.add(new JLabel("Target Device:"), c);

		c.gridx = 1;
		c.weightx = 1.0;
		main.add(deviceCombo, c);

		c.gridx = 2;
		c.weightx = 0;
		main.add(refreshBtn, c);
		row++;

		// Status
		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 3;
		c.weightx = 1.0;
		main.add(statusLabel, c);

		// Progress
		progressBar.setIndeterminate(true);
		progressBar.setVisible(false);
		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 3;
		main.add(progressBar, c);

		// Buttons
		JPanel btnPanel = new JPanel();
		btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.LINE_AXIS));
		btnPanel.setBorder(BorderFactory.createEmptyBorder(6, 16, 12, 16));
		btnPanel.add(Box.createHorizontalGlue());
		cancelBtn.addActionListener(e -> dispose());
		deployBtn.addActionListener(e -> startDeploy());
		refreshBtn.addActionListener(e -> refreshDevices());
		btnPanel.add(cancelBtn);
		btnPanel.add(Box.createRigidArea(new Dimension(8, 0)));
		btnPanel.add(deployBtn);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(main, BorderLayout.CENTER);
		getContentPane().add(btnPanel, BorderLayout.PAGE_END);
		setMinimumSize(new Dimension(560, 220));
		setSize(580, 240);
		getRootPane().setDefaultButton(deployBtn);
		commonWindowInit();
	}

	private void refreshDevices() {
		deviceCombo.setEnabled(false);
		deployBtn.setEnabled(false);
		mainWindow.getBackgroundExecutor().execute("ADB Device Discovery", () -> {
			try {
				devices = deployer.getDevices();
				SwingUtilities.invokeLater(() -> {
					DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
					int readyCount = 0;
					for (AdbDeployer.Device d : devices) {
						model.addElement(d.toString());
						if (d.isReady()) {
							readyCount++;
						}
					}
					deviceCombo.setModel(model);
					if (devices.isEmpty()) {
						statusLabel.setText("No ADB devices found. Connect a device and refresh.");
						deployBtn.setEnabled(false);
					} else if (readyCount == 0) {
						statusLabel.setText("Devices found but none are ready (offline/unauthorized).");
						deployBtn.setEnabled(false);
					} else {
						statusLabel.setText(devices.size() + " device(s) found. Ready to deploy.");
						deployBtn.setEnabled(true);
					}
					deviceCombo.setEnabled(true);
				});
			} catch (Exception e) {
				LOG.error("[QuickDeploy] ADB device discovery failed", e);
				SwingUtilities.invokeLater(() -> {
					statusLabel.setText("ADB not found. Ensure adb is on PATH or ANDROID_HOME is set.");
					deployBtn.setEnabled(false);
					deviceCombo.setEnabled(false);
				});
			}
		});
	}

	private void startDeploy() {
		int idx = deviceCombo.getSelectedIndex();
		if (idx < 0 || idx >= devices.size()) {
			JOptionPane.showMessageDialog(this, "Please select a target device.", "Quick Deploy", JOptionPane.WARNING_MESSAGE);
			return;
		}
		AdbDeployer.Device device = devices.get(idx);
		if (!device.isReady()) {
			JOptionPane.showMessageDialog(this,
					"Selected device is not ready (" + device + ").\nCheck the device screen for authorisation prompts.",
					"Quick Deploy", JOptionPane.WARNING_MESSAGE);
			return;
		}

		deployBtn.setEnabled(false);
		cancelBtn.setEnabled(false);
		progressBar.setVisible(true);
		setStatus("Preparing to deploy...");

		mainWindow.getBackgroundExecutor().execute("Quick Deploy", () -> runDeployPipeline(device));
	}

	private void runDeployPipeline(AdbDeployer.Device device) {
		Path tempApk = null;
		Path mergedApk = null;
		try {
			// 1. Detect package type
			Path origInput = getOriginalInputPath();
			if (origInput == null) {
				fail("Cannot resolve original input file. Open a supported APK/DEX/XAPK/APKS first.");
				return;
			}
			PackageType type = PackageTypeDetector.detect(origInput);
			if (type == PackageType.AAB_BUNDLE) {
				fail("AAB format is not supported for Quick Deploy.\nConvert to APKS using bundletool first.");
				return;
			}
			if (type == PackageType.UNKNOWN_OR_UNSUPPORTED || type == PackageType.RAW_DEX) {
				fail("Quick Deploy only supports APK, XAPK, and APKS formats.");
				return;
			}

			Path patchInput = origInput;

			// 2. If bundle: auto-merge to universal APK
			if (type == PackageType.XAPK_BUNDLE || type == PackageType.APKS_BUNDLE) {
				setStatus("Merging split bundle to universal APK...");
				ApkEditorBackend merger = new ApkEditorBackend();
				if (!merger.isAvailable()) {
					fail("APKEditor.jar not found. Cannot merge split bundle.\nPlace APKEditor.jar in the JADX tools/ directory.");
					return;
				}
				mergedApk = Files.createTempFile("jadx_quickdeploy_merged_", ".apk");
				merger.mergeToUniversalApk(origInput, mergedApk);
				patchInput = mergedApk;
				setStatus("Merged to universal APK.");
			}

			// 3. Patch DEX
			setStatus("Patching modified classes into APK...");
			Path unsignedTemp = Files.createTempFile("jadx_quickdeploy_unsigned_", ".apk");
			ModifiedDexManager.getInstance().patchApk(patchInput, unsignedTemp);

			// 4. ZipAlign
			setStatus("ZipAligning...");
			Path alignedTemp = Files.createTempFile("jadx_quickdeploy_aligned_", ".apk");
			ZipAligner.align(unsignedTemp, alignedTemp);
			Files.deleteIfExists(unsignedTemp);

			// 5. Sign with debug key
			setStatus("Signing with debug certificate...");
			tempApk = Files.createTempFile("jadx_quickdeploy_signed_", ".apk");
			ApkSigner.SignerConfig signerConfig = ApkSignerHelper.getDefaultDebugSignerConfig();
			ApkSignerHelper.sign(alignedTemp, tempApk, Collections.singletonList(signerConfig));
			Files.deleteIfExists(alignedTemp);

			// 6. ADB install
			PatchHistoryManager.getInstance().recordDeployMilestone(
					"Deploy to " + device.getSerial(),
					ModifiedDexManager.getInstance().getModifiedClasses().stream()
							.map(ModifiedClass::getClassType)
							.collect(Collectors.toList()));

			setStatus("Installing on device: " + device.getSerial() + "...");
			final Path finalTempApk = tempApk;
			AdbDeployer.DeployResult result = deployer.deployApk(tempApk, device.getSerial());

			if (result == AdbDeployer.DeployResult.CERT_MISMATCH) {
				// Prompt user — never silently uninstall
				String pkg = resolvePackageName(tempApk);
				final String finalPkg = pkg;
				SwingUtilities.invokeAndWait(() -> {
					int choice = JOptionPane.showConfirmDialog(
							QuickDeployDialog.this,
							"The existing app is signed with a different certificate.\n\n"
									+ "Uninstalling will erase all app data.\n\n"
									+ "[ Uninstall & Reinstall ]  or  [ Cancel ]",
							"Certificate Mismatch",
							JOptionPane.OK_CANCEL_OPTION,
							JOptionPane.WARNING_MESSAGE);
					if (choice == JOptionPane.OK_OPTION && finalPkg != null) {
						deployer.forceReinstall(finalTempApk, device.getSerial(), finalPkg);
					}
				});
			} else if (result != AdbDeployer.DeployResult.SUCCESS) {
				fail("ADB installation failed. Check logcat for details.");
				return;
			}

			SwingUtilities.invokeLater(() -> {
				progressBar.setVisible(false);
				setStatus("Deployed successfully to " + device.getSerial() + "!");
				deployBtn.setEnabled(true);
				cancelBtn.setEnabled(true);
				JOptionPane.showMessageDialog(QuickDeployDialog.this,
						"Quick Deploy successful!\nApp launched on: " + device,
						"Quick Deploy", JOptionPane.INFORMATION_MESSAGE);
				dispose();
			});

		} catch (Exception ex) {
			LOG.error("[QuickDeploy] Pipeline failed", ex);
			fail("Deploy failed: " + ex.getMessage());
		} finally {
			// Cleanup temp artifacts
			if (tempApk != null) {
				try {
					Files.deleteIfExists(tempApk);
				} catch (Exception ignored) {
				}
			}
			if (mergedApk != null) {
				try {
					Files.deleteIfExists(mergedApk);
				} catch (Exception ignored) {
				}
			}
		}
	}

	private void setStatus(String msg) {
		SwingUtilities.invokeLater(() -> statusLabel.setText(msg));
		LOG.info("[QuickDeploy] {}", msg);
	}

	private void fail(String msg) {
		SwingUtilities.invokeLater(() -> {
			progressBar.setVisible(false);
			setStatus(msg);
			deployBtn.setEnabled(true);
			cancelBtn.setEnabled(true);
			JOptionPane.showMessageDialog(this, msg, "Quick Deploy Error", JOptionPane.ERROR_MESSAGE);
		});
	}

	private Path getOriginalInputPath() {
		List<Path> openPaths = mainWindow.getProject().getFilePaths();
		for (Path p : openPaths) {
			if (p != null && Files.exists(p)) {
				String name = p.toString().toLowerCase();
				if (name.endsWith(".apk") || name.endsWith(".xapk")
						|| name.endsWith(".apks") || name.endsWith(".dex")) {
					return p;
				}
			}
		}
		return null;
	}

	private static String resolvePackageName(Path apkPath) {
		// Reuse the same heuristic from AdbDeployer
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
			if (entry == null) {
				return null;
			}
			try (java.io.InputStream in = zip.getInputStream(entry)) {
				byte[] data = in.readAllBytes();
				String raw = new String(data, java.nio.charset.StandardCharsets.UTF_16LE);
				for (String token : raw.split("\\s+")) {
					if (token.matches("[a-z][a-z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")) {
						return token;
					}
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}
}

