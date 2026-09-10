package jadx.gui.patching.history;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxDecompiler;
import jadx.gui.ui.codearea.AbstractCodeArea;
import jadx.api.JavaClass;
import jadx.core.Consts;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.gui.JadxWrapper;
import jadx.gui.patching.ModifiedClass;
import jadx.gui.patching.ModifiedDexManager;
import jadx.gui.patching.history.PatchHistoryManager.ModifiedClassSummary;
import jadx.gui.treemodel.JClass;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.codearea.ClassCodeContentPanel;
import jadx.gui.ui.codearea.SmaliArea;
import jadx.gui.ui.dialog.CommonDialog;
import jadx.gui.ui.panel.ContentPanel;
import jadx.gui.utils.UiUtils;

/**
 * State-of-the-Art Global Patch History & Changes Hub.
 * <p>
 * Displays a master-detail dashboard of all modified classes across the session and Git history,
 * allowing instant navigation (jump to Smali editor), checkpoint inspection, side-by-side diffs,
 * and rollback without requiring any tabs to be currently open.
 */
public class GlobalPatchHistoryDialog extends CommonDialog {
	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(GlobalPatchHistoryDialog.class);

	private final String initialClassToSelect;
	private final Map<String, ModifiedClassSummary> classOverview;
	private final List<ModifiedClassSummary> summaryList;

	private static class ActiveChangeItem {
		final SmaliMethodChange methodChange;
		@Nullable
		final SmaliHunk hunk;

		ActiveChangeItem(SmaliMethodChange methodChange, @Nullable SmaliHunk hunk) {
			this.methodChange = methodChange;
			this.hunk = hunk;
		}
	}

	// UI Components - Master
	private JTable classesTable;
	private DefaultTableModel classesTableModel;
	private TableRowSorter<DefaultTableModel> classesSorter;
	private JTextField searchField;
	private JButton openInEditorBtn;

	// UI Components - Detail Panel
	private JLabel detailHeaderLabel;
	private JTabbedPane detailTabbedPane;

	// Tab 1: Active Changes & Diffs
	private JTable activeChangesTable;
	private DefaultTableModel activeChangesTableModel;
	private final List<ActiveChangeItem> activeChangeItems = new ArrayList<>();
	private JLabel drawerTitleLabel;
	private JLabel drawerStatsLabel;
	private RSyntaxTextArea drawerBeforeArea;
	private RSyntaxTextArea drawerAfterArea;
	private JButton revertHunkBtn;
	private JButton revertMethodBtn;
	private JButton jumpHunkBtn;

	// Tab 2: Checkpoint Timeline
	private JTable checkpointsTable;
	private DefaultTableModel checkpointsTableModel;
	private List<PatchCommit> currentHistory = Collections.emptyList();
	private String currentSelectedClass = null;
	private JButton viewDiffBtn;
	private JButton compareCurrentBtn;
	private JButton rollbackBtn;

	// Bottom Bar
	private JLabel bottomStatusLabel;
	private JButton compareBaselineBtn;
	private JButton saveCheckpointBtn;

	public GlobalPatchHistoryDialog(MainWindow mainWindow, String preferredClass) {
		super(mainWindow);
		this.initialClassToSelect = (preferredClass != null) ? PatchHistoryManager.normalizeClassType(preferredClass) : null;
		this.classOverview = PatchHistoryManager.getInstance().getModifiedClassesOverview();
		this.summaryList = new ArrayList<>(classOverview.values());

		setTitle("Patch Workspace & Changes Hub — Project Overview");
		initUI();
	}

	private void initUI() {
		if (summaryList.isEmpty()) {
			initEmptyStateUI();
			return;
		}

		JPanel rootPanel = new JPanel(new BorderLayout(8, 8));
		rootPanel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

		// Top Header Bar
		rootPanel.add(createHeaderPanel(), BorderLayout.NORTH);

		// Center Master-Detail Split Pane
		JPanel masterPanel = createMasterPanel();
		masterPanel.setPreferredSize(new Dimension(320, 500));
		masterPanel.setMinimumSize(new Dimension(260, 200));

		JPanel detailPanel = createDetailPanel();
		detailPanel.setPreferredSize(new Dimension(740, 500));
		detailPanel.setMinimumSize(new Dimension(450, 200));

		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, masterPanel, detailPanel);
		splitPane.setResizeWeight(0.30);
		splitPane.setDividerLocation(320);
		rootPanel.add(splitPane, BorderLayout.CENTER);

		// Bottom Action Bar
		rootPanel.add(createBottomBar(), BorderLayout.SOUTH);

		getContentPane().add(rootPanel);
		pack();
		setSize(1060, 680);
		setLocationRelativeTo(mainWindow);

		SwingUtilities.invokeLater(() -> splitPane.setDividerLocation(320));

		// Select initial class or first row
		selectInitialRow();
	}

	private JPanel createHeaderPanel() {
		JPanel headerPanel = new JPanel(new BorderLayout(10, 6));

		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.PAGE_AXIS));

		JLabel titleLbl = new JLabel("🛡️ Patch History & Changes Hub");
		titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD, 15f));
		infoPanel.add(titleLbl);

		int inMemoryCount = 0;
		int totalCheckpoints = 0;
		for (ModifiedClassSummary s : summaryList) {
			if (s.isInMemoryModified()) {
				inMemoryCount++;
			}
			totalCheckpoints += s.getCheckpointCount();
		}

		JLabel subtitleLbl = new JLabel(String.format(
				"Tracking %d modified class%s (%d active in-memory) • %d total checkpoints across project",
				summaryList.size(), summaryList.size() == 1 ? "" : "es", inMemoryCount, totalCheckpoints));
		subtitleLbl.setForeground(Color.GRAY);
		infoPanel.add(Box.createVerticalStrut(2));
		infoPanel.add(subtitleLbl);

		headerPanel.add(infoPanel, BorderLayout.WEST);

		// Search Bar on right
		JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		searchPanel.add(new JLabel("🔍 Filter:"));
		searchField = new JTextField(18);
		searchField.setToolTipText("Filter by class name, package, or DEX entry");
		searchField.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				updateFilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				updateFilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				updateFilter();
			}
		});
		searchPanel.add(searchField);

		headerPanel.add(searchPanel, BorderLayout.EAST);
		headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		return headerPanel;
	}

	private JPanel createMasterPanel() {
		JPanel panel = new JPanel(new BorderLayout(6, 6));
		panel.setBorder(BorderFactory.createTitledBorder("Modified Classes (" + summaryList.size() + ")"));

		String[] cols = {"Status", "Class", "DEX", "Edits"};
		classesTableModel = new DefaultTableModel(cols, 0) {
			@Override
			public boolean isCellEditable(int r, int c) {
				return false;
			}
		};

		for (ModifiedClassSummary summary : summaryList) {
			String status = summary.isInMemoryModified() ? "● Active" : "○ History";
			String displayName = getShortClassName(summary.getClassType());
			if (!summary.getInnerClasses().isEmpty()) {
				displayName += " (+" + summary.getInnerClasses().size() + " inner)";
			}
			classesTableModel.addRow(new Object[]{
					status,
					displayName,
					summary.getDexName(),
					summary.getCheckpointCount() > 0 ? String.valueOf(summary.getCheckpointCount()) : "1"
			});
		}

		classesTable = new JTable(classesTableModel);
		classesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		classesTable.setRowHeight(24);

		// Custom renderer for Status column
		classesTable.getColumnModel().getColumn(0).setPreferredWidth(65);
		classesTable.getColumnModel().getColumn(0).setMaxWidth(80);
		classesTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
				Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
				if ("● Active".equals(value)) {
					c.setForeground(new Color(0, 150, 0));
					setFont(getFont().deriveFont(Font.BOLD));
				} else {
					c.setForeground(Color.GRAY);
					setFont(getFont().deriveFont(Font.PLAIN));
				}
				return c;
			}
		});

		classesTable.getColumnModel().getColumn(1).setPreferredWidth(160);
		classesTable.getColumnModel().getColumn(2).setPreferredWidth(80);
		classesTable.getColumnModel().getColumn(3).setPreferredWidth(45);
		classesTable.getColumnModel().getColumn(3).setMaxWidth(55);

		DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
		centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
		classesTable.getColumnModel().getColumn(2).setCellRenderer(centerRenderer);
		classesTable.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);

		// Set tooltips for class names to show full package
		classesTable.addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				int row = classesTable.rowAtPoint(e.getPoint());
				if (row >= 0 && row < classesTable.getRowCount()) {
					int modelRow = classesTable.convertRowIndexToModel(row);
					if (modelRow >= 0 && modelRow < summaryList.size()) {
						ModifiedClassSummary s = summaryList.get(modelRow);
						String tip = s.getClassType();
						if (!s.getInnerClasses().isEmpty()) {
							tip += " (Includes inner classes: " + String.join(", ", s.getInnerClasses()) + ")";
						}
						classesTable.setToolTipText(tip);
					}
				}
			}
		});

		classesSorter = new TableRowSorter<>(classesTableModel);
		classesTable.setRowSorter(classesSorter);

		classesTable.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				onClassSelected();
			}
		});

		classesTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					openSelectedClassInEditor();
				}
			}
		});

		panel.add(new JScrollPane(classesTable), BorderLayout.CENTER);

		// Button below left table
		JPanel leftBtnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		openInEditorBtn = new JButton("Jump to Smali Editor", UiUtils.openSvgIcon("ui/run"));
		openInEditorBtn.setToolTipText("Open class in editor and automatically switch to Smali view");
		openInEditorBtn.addActionListener(e -> openSelectedClassInEditor());
		leftBtnBar.add(openInEditorBtn);

		panel.add(leftBtnBar, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel createDetailPanel() {
		JPanel panel = new JPanel(new BorderLayout(6, 6));
		panel.setBorder(BorderFactory.createTitledBorder("Workspace Details"));

		detailHeaderLabel = new JLabel("Select a class from the list to view its history.");
		detailHeaderLabel.setFont(detailHeaderLabel.getFont().deriveFont(Font.BOLD, 12f));
		detailHeaderLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
		panel.add(detailHeaderLabel, BorderLayout.NORTH);

		detailTabbedPane = new JTabbedPane();
		detailTabbedPane.addTab("Active Changes", UiUtils.openSvgIcon("nodes/method"), createActiveChangesPanel());
		detailTabbedPane.addTab("Checkpoint Timeline", UiUtils.openSvgIcon("ui/dataView"), createTimelinePanel());

		panel.add(detailTabbedPane, BorderLayout.CENTER);
		return panel;
	}

	private JPanel createActiveChangesPanel() {
		JPanel panel = new JPanel(new BorderLayout(4, 4));

		String[] colNames = {"Method / Scope", "Change #", "Type", "Line", "Summary"};
		activeChangesTableModel = new DefaultTableModel(colNames, 0) {
			@Override
			public boolean isCellEditable(int r, int c) {
				return false;
			}
		};

		activeChangesTable = new JTable(activeChangesTableModel);
		activeChangesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		activeChangesTable.setRowHeight(24);

		activeChangesTable.getColumnModel().getColumn(0).setPreferredWidth(200);
		activeChangesTable.getColumnModel().getColumn(1).setPreferredWidth(80);
		activeChangesTable.getColumnModel().getColumn(1).setMaxWidth(100);
		activeChangesTable.getColumnModel().getColumn(2).setPreferredWidth(85);
		activeChangesTable.getColumnModel().getColumn(2).setMaxWidth(105);
		activeChangesTable.getColumnModel().getColumn(3).setPreferredWidth(65);
		activeChangesTable.getColumnModel().getColumn(3).setMaxWidth(80);
		activeChangesTable.getColumnModel().getColumn(4).setPreferredWidth(180);

		// Bold method name renderer
		activeChangesTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
				Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
				if (!isSelected) {
					setFont(getFont().deriveFont(Font.BOLD));
				}
				return c;
			}
		});

		// Center alignment for Change #
		DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
		centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
		activeChangesTable.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);

		// Colored badge renderer for Type
		activeChangesTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
				Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
				setHorizontalAlignment(SwingConstants.CENTER);
				if (!isSelected && value != null) {
					String str = value.toString().toUpperCase();
					setFont(getFont().deriveFont(Font.BOLD));
					if (str.contains("REPLACE") || str.contains("MODIFIED")) {
						setForeground(new Color(217, 119, 6)); // Amber
					} else if (str.contains("INSERT") || str.contains("ADDED")) {
						setForeground(new Color(22, 163, 74)); // Green
					} else if (str.contains("DELETE") || str.contains("DELETED")) {
						setForeground(new Color(220, 38, 38)); // Red
					}
				}
				return c;
			}
		});

		// Muted line number renderer
		activeChangesTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
				Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
				setHorizontalAlignment(SwingConstants.CENTER);
				if (!isSelected) {
					setForeground(new Color(100, 116, 139));
				}
				return c;
			}
		});

		// Detailed tooltip for full signature and hunk info
		activeChangesTable.addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				int row = activeChangesTable.rowAtPoint(e.getPoint());
				if (row >= 0 && row < activeChangeItems.size()) {
					ActiveChangeItem item = activeChangeItems.get(row);
					String tip = "<html><b>Method Signature:</b> " + item.methodChange.getMethodSignature()
							+ "<br><b>Class:</b> " + item.methodChange.getClassType();
					if (item.hunk != null) {
						tip += "<br><b>Hunk #" + item.hunk.getHunkIndex() + ":</b> "
								+ item.hunk.getOriginalLines().size() + " baseline line(s) replaced by "
								+ item.hunk.getModifiedLines().size() + " active line(s)";
					}
					tip += "</html>";
					activeChangesTable.setToolTipText(tip);
				}
			}
		});

		activeChangesTable.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				onActiveChangeSelected();
			}
		});

		activeChangesTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					jumpToSelectedHunkInEditor();
				}
			}
		});

		JScrollPane tableScroll = new JScrollPane(activeChangesTable);
		tableScroll.setPreferredSize(new Dimension(500, 130));
		tableScroll.setMinimumSize(new Dimension(300, 80));

		JPanel drawerPanel = createDiffDrawer();
		drawerPanel.setPreferredSize(new Dimension(500, 360));
		drawerPanel.setMinimumSize(new Dimension(300, 220));

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, drawerPanel);
		split.setResizeWeight(0.25);
		split.setDividerLocation(130);

		panel.add(split, BorderLayout.CENTER);
		SwingUtilities.invokeLater(() -> split.setDividerLocation(130));
		return panel;
	}

	private JPanel createDiffDrawer() {
		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.setBorder(BorderFactory.createTitledBorder("Change Diff Drawer"));

		JPanel header = new JPanel(new BorderLayout());
		drawerTitleLabel = new JLabel("Select a change above to view diff.");
		drawerTitleLabel.setFont(drawerTitleLabel.getFont().deriveFont(Font.BOLD, 11.5f));
		drawerStatsLabel = new JLabel("");
		drawerStatsLabel.setForeground(Color.GRAY);
		drawerStatsLabel.setFont(drawerStatsLabel.getFont().deriveFont(11f));
		header.add(drawerTitleLabel, BorderLayout.WEST);
		header.add(drawerStatsLabel, BorderLayout.EAST);
		header.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
		panel.add(header, BorderLayout.NORTH);

		JPanel leftPanel = new JPanel(new BorderLayout(2, 2));
		JLabel leftLbl = new JLabel("BEFORE (Baseline)");
		leftLbl.setFont(leftLbl.getFont().deriveFont(Font.BOLD, 10.5f));
		drawerBeforeArea = createCodeArea("");
		RTextScrollPane leftScroll = new RTextScrollPane(drawerBeforeArea);
		leftPanel.add(leftLbl, BorderLayout.NORTH);
		leftPanel.add(leftScroll, BorderLayout.CENTER);

		JPanel rightPanel = new JPanel(new BorderLayout(2, 2));
		JLabel rightLbl = new JLabel("AFTER (Working Tree)");
		rightLbl.setFont(rightLbl.getFont().deriveFont(Font.BOLD, 10.5f));
		drawerAfterArea = createCodeArea("");
		RTextScrollPane rightScroll = new RTextScrollPane(drawerAfterArea);
		rightPanel.add(rightLbl, BorderLayout.NORTH);
		rightPanel.add(rightScroll, BorderLayout.CENTER);

		JSplitPane diffSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
		diffSplit.setResizeWeight(0.5);
		diffSplit.setDividerLocation(260);
		panel.add(diffSplit, BorderLayout.CENTER);
		SwingUtilities.invokeLater(() -> diffSplit.setDividerLocation(0.5));

		JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		revertHunkBtn = new JButton("Revert This Change", UiUtils.openSvgIcon("ui/reset"));
		revertHunkBtn.setEnabled(false);
		revertHunkBtn.setToolTipText("Selectively undoes only this change in Working Tree");
		revertHunkBtn.addActionListener(e -> revertSelectedHunk());

		revertMethodBtn = new JButton("Revert Entire Method", UiUtils.openSvgIcon("ui/reset"));
		revertMethodBtn.setEnabled(false);
		revertMethodBtn.setToolTipText("Reverts the entire method back to baseline in Working Tree");
		revertMethodBtn.addActionListener(e -> revertSelectedMethod());

		jumpHunkBtn = new JButton("Jump to Smali", UiUtils.openSvgIcon("ui/run"));
		jumpHunkBtn.setEnabled(false);
		jumpHunkBtn.setToolTipText("Opens this exact line in Smali Editor");
		jumpHunkBtn.addActionListener(e -> jumpToSelectedHunkInEditor());

		btnBar.add(revertHunkBtn);
		btnBar.add(revertMethodBtn);
		btnBar.add(jumpHunkBtn);

		panel.add(btnBar, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel createTimelinePanel() {
		JPanel panel = new JPanel(new BorderLayout(6, 6));

		String[] colNames = {"ID", "Time", "Checkpoint / Message", "Type"};
		checkpointsTableModel = new DefaultTableModel(colNames, 0) {
			@Override
			public boolean isCellEditable(int r, int c) {
				return false;
			}
		};

		checkpointsTable = new JTable(checkpointsTableModel);
		checkpointsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		checkpointsTable.setRowHeight(22);
		checkpointsTable.getColumnModel().getColumn(0).setPreferredWidth(65);
		checkpointsTable.getColumnModel().getColumn(0).setMaxWidth(75);
		checkpointsTable.getColumnModel().getColumn(1).setPreferredWidth(125);
		checkpointsTable.getColumnModel().getColumn(2).setPreferredWidth(210);
		checkpointsTable.getColumnModel().getColumn(3).setPreferredWidth(110);

		DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
		centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
		checkpointsTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
		checkpointsTable.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);

		checkpointsTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
				Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
				if (!isSelected && value != null) {
					String str = value.toString();
					setFont(getFont().deriveFont(Font.BOLD));
					if (str.contains("Deploy") || str.contains("Active on Device")) {
						setForeground(new Color(22, 163, 74));
					} else if (str.contains("Baseline")) {
						setForeground(new Color(2, 132, 199));
					} else if (str.contains("Milestone")) {
						setForeground(new Color(147, 51, 234));
					}
				}
				return c;
			}
		});

		checkpointsTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					showDiffForSelectedCheckpoint();
				}
			}
		});

		panel.add(new JScrollPane(checkpointsTable), BorderLayout.CENTER);

		JPanel detailBtnBar = new JPanel();
		detailBtnBar.setLayout(new BoxLayout(detailBtnBar, BoxLayout.LINE_AXIS));
		detailBtnBar.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));

		viewDiffBtn = new JButton("🔍 View Changes in Checkpoint");
		viewDiffBtn.setToolTipText("Shows side-by-side diff between this checkpoint and previous state");
		viewDiffBtn.addActionListener(e -> showDiffForSelectedCheckpoint());

		compareCurrentBtn = new JButton("🔍 Compare vs Current");
		compareCurrentBtn.setToolTipText("Compares selected checkpoint with current active Smali code");
		compareCurrentBtn.addActionListener(e -> showDiffWithCurrent());

		rollbackBtn = new JButton("⏪ Rollback");
		rollbackBtn.setToolTipText("Revert class code to this checkpoint");
		rollbackBtn.addActionListener(e -> rollbackSelectedCheckpoint());

		detailBtnBar.add(viewDiffBtn);
		detailBtnBar.add(Box.createRigidArea(new Dimension(6, 0)));
		detailBtnBar.add(compareCurrentBtn);
		detailBtnBar.add(Box.createRigidArea(new Dimension(6, 0)));
		detailBtnBar.add(rollbackBtn);

		panel.add(detailBtnBar, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel createBottomBar() {
		JPanel bar = new JPanel(new BorderLayout(8, 4));
		bar.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		bottomStatusLabel = new JLabel("BASELINE  →  WORKING TREE");
		bottomStatusLabel.setFont(bottomStatusLabel.getFont().deriveFont(Font.BOLD, 11f));
		bottomStatusLabel.setForeground(new Color(0, 120, 215));
		bar.add(bottomStatusLabel, BorderLayout.WEST);

		JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

		compareBaselineBtn = new JButton("🔍 Compare with Baseline");
		compareBaselineBtn.addActionListener(e -> showDiffWithBaseline());

		saveCheckpointBtn = new JButton("💾 Save Checkpoint");
		saveCheckpointBtn.addActionListener(e -> saveCheckpointForSelectedClass());

		JButton deployBtn = new JButton("📱 Quick Deploy (Ctrl+Shift+R)");
		deployBtn.addActionListener(e -> {
			dispose();
			mainWindow.openQuickDeploy();
		});

		JButton closeBtn = new JButton("Close");
		closeBtn.addActionListener(e -> dispose());

		rightBtns.add(compareBaselineBtn);
		rightBtns.add(saveCheckpointBtn);
		rightBtns.add(deployBtn);
		rightBtns.add(closeBtn);
		bar.add(rightBtns, BorderLayout.EAST);

		return bar;
	}

	private RSyntaxTextArea createCodeArea(String text) {
		RSyntaxTextArea area = new RSyntaxTextArea(text);
		area.setEditable(false);
		area.setSyntaxEditingStyle(AbstractCodeArea.SYNTAX_STYLE_SMALI);
		area.setCodeFoldingEnabled(false);
		area.setAntiAliasingEnabled(true);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		if (mainWindow != null && mainWindow.getEditorThemeManager() != null) {
			mainWindow.getEditorThemeManager().apply(area);
		}
		area.setHighlightCurrentLine(false);
		return area;
	}

	private void initEmptyStateUI() {
		JPanel emptyPanel = new JPanel(new GridBagLayout());
		emptyPanel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.insets = new Insets(6, 6, 6, 6);
		c.anchor = GridBagConstraints.CENTER;

		JLabel iconLabel = new JLabel("🛡️");
		iconLabel.setFont(iconLabel.getFont().deriveFont(36f));
		emptyPanel.add(iconLabel, c);

		c.gridy++;
		JLabel titleLabel = new JLabel("No Modified Classes in this Project");
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
		emptyPanel.add(titleLabel, c);

		c.gridy++;
		JLabel msgLabel = new JLabel("<html><center>"
				+ "You haven't modified any classes in this session yet.<br><br>"
				+ "<b>To start tracking patches:</b><br>"
				+ "1. Open any class in the package tree.<br>"
				+ "2. Switch to the <b>Smali</b> view tab.<br>"
				+ "3. Edit the assembly instructions and press <b>Ctrl+S</b>.<br><br>"
				+ "Checkpoints and diffs will be tracked automatically here."
				+ "</center></html>");
		msgLabel.setForeground(Color.DARK_GRAY);
		emptyPanel.add(msgLabel, c);

		c.gridy++;
		JButton okBtn = new JButton("OK");
		okBtn.addActionListener(e -> dispose());
		emptyPanel.add(okBtn, c);

		getContentPane().add(emptyPanel);
		pack();
		setSize(500, 320);
		setLocationRelativeTo(mainWindow);
	}

	private void updateFilter() {
		String text = searchField.getText();
		if (text == null || text.trim().isEmpty()) {
			classesSorter.setRowFilter(null);
		} else {
			classesSorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text.trim())));
		}
	}

	private void selectInitialRow() {
		if (classesTable.getRowCount() == 0) {
			return;
		}
		int rowToSelect = 0;
		if (initialClassToSelect != null) {
			String rootInitial = PatchHistoryManager.getRootClassName(initialClassToSelect);
			String canonInitial = PatchHistoryManager.canonicalClassType(rootInitial);
			for (int i = 0; i < summaryList.size(); i++) {
				String summaryCanon = PatchHistoryManager.canonicalClassType(summaryList.get(i).getClassType());
				if (summaryCanon.equals(canonInitial)) {
					int viewIndex = classesTable.convertRowIndexToView(i);
					if (viewIndex >= 0) {
						rowToSelect = viewIndex;
					}
					break;
				}
			}
		}
		classesTable.setRowSelectionInterval(rowToSelect, rowToSelect);
	}

	private void onClassSelected() {
		int viewRow = classesTable.getSelectedRow();
		if (viewRow < 0 || viewRow >= classesTable.getRowCount()) {
			currentSelectedClass = null;
			currentHistory = Collections.emptyList();
			checkpointsTableModel.setRowCount(0);
			detailHeaderLabel.setText("Select a class to view its history.");
			enableDetailButtons(false);
			return;
		}

		int modelRow = classesTable.convertRowIndexToModel(viewRow);
		ModifiedClassSummary summary = summaryList.get(modelRow);
		currentSelectedClass = summary.getClassType();

		detailHeaderLabel.setText(String.format("Class: %s  [%s]%s", currentSelectedClass, summary.getDexName(), summary.getInnerClassesSummary()));

		// Lazy O(C) history lookup for selected class
		currentHistory = PatchHistoryManager.getInstance().getClassHistory(currentSelectedClass);
		checkpointsTableModel.setRowCount(0);

		// Find latest actual code edit for (Code HEAD)
		int codeHeadIndex = -1;
		for (int i = 0; i < currentHistory.size(); i++) {
			PatchCommit c = currentHistory.get(i);
			if (c.isCodeEdit()) {
				codeHeadIndex = i;
				break;
			}
		}

		for (int i = 0; i < currentHistory.size(); i++) {
			PatchCommit commit = currentHistory.get(i);
			String type = "Edit";
			if (commit.isBaseline()) {
				type = "Original Baseline";
			} else if (commit.isDeployMilestone()) {
				type = "🚀 Deploy Milestone (Active on Device)";
			} else if (commit.isExportMilestone()) {
				type = "📦 Export Milestone";
			} else if (commit.isReloadSnapshot()) {
				type = "🔄 Reload Snapshot";
			}
			if (i == codeHeadIndex) {
				type += " (Code HEAD)";
			}
			checkpointsTableModel.addRow(new Object[]{
					commit.getShortHash(),
					commit.getFormattedTime(),
					commit.getMessage(),
					type
			});
		}

		if (checkpointsTable.getRowCount() > 0) {
			checkpointsTable.setRowSelectionInterval(0, 0);
			enableDetailButtons(true);
		} else {
			enableDetailButtons(false);
		}

		// 2. Populate Active Changes (Working Tree vs Baseline)
		String baselineSmali = PatchHistoryManager.getInstance().getBaselineSmali(currentSelectedClass);
		String currentSmali = getCurrentActiveSmali(currentSelectedClass);

		activeChangeItems.clear();
		activeChangesTableModel.setRowCount(0);

		List<SmaliMethodChange> changes = SmaliMethodDiffParser.parseChanges(currentSelectedClass, baselineSmali, currentSmali);
		for (SmaliMethodChange change : changes) {
			String methodDisplay = SmaliMethodDiffParser.formatHumanReadableSignature(change.getMethodSignature());
			if (methodDisplay == null || methodDisplay.isEmpty()) {
				methodDisplay = change.getMethodName() + (change.isMethod() ? "()" : "");
			}

			if (change.hasHunks()) {
				for (SmaliHunk hunk : change.getHunks()) {
					activeChangeItems.add(new ActiveChangeItem(change, hunk));
					String changeSummary = SmaliMethodDiffParser.formatHunkSummary(hunk);
					activeChangesTableModel.addRow(new Object[]{
							methodDisplay,
							"Change #" + hunk.getHunkIndex(),
							hunk.getHunkType().name(),
							"L" + hunk.getStartLineInEditor(),
							changeSummary
					});
				}
			} else {
				activeChangeItems.add(new ActiveChangeItem(change, null));
				String changeSummary;
				if (change.isAddedMethod()) {
					changeSummary = "+ Added method";
				} else if (change.isDeletedMethod()) {
					changeSummary = "- Deleted method";
				} else if (change.getChangeType() == SmaliMethodChange.ChangeType.FIELD_MODIFIED) {
					changeSummary = "Field modified";
				} else {
					changeSummary = "Method modified";
				}
				activeChangesTableModel.addRow(new Object[]{
						methodDisplay,
						"Entire " + (change.isMethod() ? "Method" : "Field"),
						change.getChangeType().name(),
						change.getStartLineInDocument() > 0 ? "L" + change.getStartLineInDocument() : "-",
						changeSummary
				});
			}
		}

		int activeCount = activeChangeItems.size();
		detailTabbedPane.setTitleAt(0, "Active Changes (" + activeCount + ")");
		detailTabbedPane.setTitleAt(1, "Checkpoint Timeline (" + currentHistory.size() + ")");

		String baselineHash = !currentHistory.isEmpty() ? currentHistory.get(currentHistory.size() - 1).getShortHash() : "none";
		if (activeCount > 0) {
			bottomStatusLabel.setText(String.format("BASELINE %s  →  WORKING TREE  (● %d active change%s)",
					baselineHash, activeCount, activeCount > 1 ? "s" : ""));
			bottomStatusLabel.setForeground(new Color(0, 150, 0));
			activeChangesTable.setRowSelectionInterval(0, 0);
			detailTabbedPane.setSelectedIndex(0);
		} else {
			bottomStatusLabel.setText(String.format("BASELINE %s  →  WORKING TREE  (✓ Clean)", baselineHash));
			bottomStatusLabel.setForeground(Color.GRAY);
			onActiveChangeSelected();
			if (checkpointsTable.getRowCount() > 0) {
				detailTabbedPane.setSelectedIndex(1);
			}
		}
	}

	private void onActiveChangeSelected() {
		int row = activeChangesTable.getSelectedRow();
		if (row < 0 || row >= activeChangeItems.size()) {
			drawerTitleLabel.setText("Select a change above to view diff.");
			drawerStatsLabel.setText("");
			drawerBeforeArea.setText("");
			drawerAfterArea.setText("");
			revertHunkBtn.setEnabled(false);
			revertMethodBtn.setEnabled(false);
			jumpHunkBtn.setEnabled(false);
			return;
		}

		ActiveChangeItem item = activeChangeItems.get(row);
		String methodDisplay = SmaliMethodDiffParser.formatHumanReadableSignature(item.methodChange.getMethodSignature());
		if (methodDisplay == null || methodDisplay.isEmpty()) {
			methodDisplay = item.methodChange.getMethodName() + (item.methodChange.isMethod() ? "()" : "");
		}

		if (item.hunk != null) {
			drawerTitleLabel.setText("Change #" + item.hunk.getHunkIndex() + " in " + methodDisplay);
			int modLines = item.hunk.getModifiedLines().size();
			int origLines = item.hunk.getOriginalLines().size();
			drawerStatsLabel.setText(String.format("-%d lines / +%d lines (Line %d)", origLines, modLines, item.hunk.getStartLineInEditor()));
			drawerBeforeArea.setText(String.join("\n", item.hunk.getOriginalLines()));
			drawerAfterArea.setText(String.join("\n", item.hunk.getModifiedLines()));
			drawerBeforeArea.setCaretPosition(0);
			drawerAfterArea.setCaretPosition(0);
			revertHunkBtn.setEnabled(true);
			revertMethodBtn.setEnabled(true);
			jumpHunkBtn.setEnabled(true);
		} else {
			drawerTitleLabel.setText(methodDisplay + " (" + item.methodChange.getChangeType().name() + ")");
			drawerStatsLabel.setText("Method-level change (Line " + item.methodChange.getStartLineInDocument() + ")");
			drawerBeforeArea.setText(item.methodChange.getBeforeMethodCode());
			drawerAfterArea.setText(item.methodChange.getAfterMethodCode());
			drawerBeforeArea.setCaretPosition(0);
			drawerAfterArea.setCaretPosition(0);
			revertHunkBtn.setEnabled(false);
			revertMethodBtn.setEnabled(true);
			jumpHunkBtn.setEnabled(true);
		}
	}

	private void revertSelectedHunk() {
		int row = activeChangesTable.getSelectedRow();
		if (row < 0 || row >= activeChangeItems.size() || currentSelectedClass == null) {
			return;
		}
		ActiveChangeItem item = activeChangeItems.get(row);
		if (item.hunk == null) {
			return;
		}

		String methodDisplay = SmaliMethodDiffParser.formatHumanReadableSignature(item.methodChange.getMethodSignature());
		if (methodDisplay == null || methodDisplay.isEmpty()) {
			methodDisplay = item.methodChange.getMethodName() + "()";
		}

		int confirm = JOptionPane.showConfirmDialog(this,
				"Revert Change #" + item.hunk.getHunkIndex() + " in " + methodDisplay + "?",
				"Confirm Selective Revert", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}

		jumpToClassInSmali(mainWindow, currentSelectedClass, sa -> {
			boolean ok = sa.revertHunk(item.hunk);
			if (ok) {
				refreshCurrentClassState();
			}
		});
	}

	private void revertSelectedMethod() {
		int row = activeChangesTable.getSelectedRow();
		if (row < 0 || row >= activeChangeItems.size() || currentSelectedClass == null) {
			return;
		}
		ActiveChangeItem item = activeChangeItems.get(row);

		String methodDisplay = SmaliMethodDiffParser.formatHumanReadableSignature(item.methodChange.getMethodSignature());
		if (methodDisplay == null || methodDisplay.isEmpty()) {
			methodDisplay = item.methodChange.getMethodName() + "()";
		}

		int confirm = JOptionPane.showConfirmDialog(this,
				"Revert entire method " + methodDisplay + " back to baseline?",
				"Confirm Method Revert", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}

		jumpToClassInSmali(mainWindow, currentSelectedClass, sa -> {
			boolean ok = sa.revertMethod(item.methodChange);
			if (ok) {
				refreshCurrentClassState();
			}
		});
	}

	private void jumpToSelectedHunkInEditor() {
		int row = activeChangesTable.getSelectedRow();
		if (row < 0 || row >= activeChangeItems.size() || currentSelectedClass == null) {
			return;
		}
		ActiveChangeItem item = activeChangeItems.get(row);
		int targetLine = (item.hunk != null) ? item.hunk.getStartLineInEditor() : item.methodChange.getStartLineInDocument();

		jumpToClassInSmali(mainWindow, currentSelectedClass, sa -> {
			if (targetLine > 0 && targetLine <= sa.getLineCount()) {
				try {
					int offset = sa.getLineStartOffset(targetLine - 1);
					sa.setCaretPosition(offset);
				} catch (Exception ignored) {
				}
			}
			sa.requestFocus();
		});
		dispose();
	}

	private void saveCheckpointForSelectedClass() {
		if (currentSelectedClass == null) {
			return;
		}
		String msg = JOptionPane.showInputDialog(this,
				"Enter description for new Checkpoint:",
				"Save Checkpoint — " + getShortClassName(currentSelectedClass),
				JOptionPane.PLAIN_MESSAGE);
		if (msg == null) {
			return;
		}
		msg = msg.trim();
		if (msg.isEmpty()) {
			msg = "Checkpoint: " + getShortClassName(currentSelectedClass);
		}

		String currentSmali = getCurrentActiveSmali(currentSelectedClass);
		PatchHistoryManager.getInstance().recordEdit(currentSelectedClass, currentSmali, msg);
		refreshCurrentClassState();
		UiUtils.showToast(mainWindow, "✓ Checkpoint saved: " + msg);
	}

	private void showDiffWithBaseline() {
		if (currentSelectedClass == null) {
			return;
		}
		String baselineSmali = PatchHistoryManager.getInstance().getBaselineSmali(currentSelectedClass);
		if (baselineSmali == null) {
			JOptionPane.showMessageDialog(this, "No baseline smali found for " + currentSelectedClass, "Diff", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		String currentSmali = getCurrentActiveSmali(currentSelectedClass);
		PatchDiffDialog diffDialog = new PatchDiffDialog(
				mainWindow, currentSelectedClass,
				"Diff: Baseline vs Current Working Tree — " + getShortClassName(currentSelectedClass),
				baselineSmali, "Original APK Baseline",
				currentSmali, "Current Working Tree",
				null
		);
		diffDialog.setVisible(true);
	}

	private void refreshCurrentClassState() {
		onClassSelected();
	}

	private void enableDetailButtons(boolean enable) {
		viewDiffBtn.setEnabled(enable);
		compareCurrentBtn.setEnabled(enable);
		rollbackBtn.setEnabled(enable);
	}

	private void openSelectedClassInEditor() {
		if (currentSelectedClass == null) {
			return;
		}
		boolean jumped = jumpToClassInSmali(mainWindow, currentSelectedClass, SmaliArea::requestFocus);
		if (jumped) {
			dispose();
		} else {
			JOptionPane.showMessageDialog(this,
					"Could not locate class '" + currentSelectedClass + "' in active project.",
					"Open Class", JOptionPane.WARNING_MESSAGE);
		}
	}

	public static JavaClass findJavaClass(MainWindow mainWindow, String classType) {
		if (mainWindow == null || mainWindow.getWrapper() == null || mainWindow.getWrapper().getDecompiler() == null) {
			return null;
		}
		String clean = PatchHistoryManager.normalizeClassType(classType);
		if (clean.isEmpty()) {
			return null;
		}

		JadxWrapper wrapper = mainWindow.getWrapper();
		JadxDecompiler decompiler = wrapper.getDecompiler();
		RootNode root = decompiler.getRoot();

		// 1. Try search by full alias (matches "defpackage.zl0" or renamed classes)
		JavaClass jc = wrapper.searchJavaClassByFullAlias(clean);
		if (jc != null) {
			return jc;
		}

		// 2. Try search by orig class name / raw name
		jc = wrapper.searchJavaClassByOrigClassName(clean);
		if (jc != null) {
			return jc;
		}
		jc = wrapper.searchJavaClassByRawName(clean);
		if (jc != null) {
			return jc;
		}
		jc = decompiler.searchJavaClassOrItsParentByOrigFullName(clean);
		if (jc != null) {
			return jc;
		}

		// 3. Try resolving directly in RootNode
		if (root != null) {
			ClassNode clsNode = root.resolveClass(ArgType.object(clean));
			if (clsNode != null && clsNode.getJavaNode() != null) {
				return clsNode.getJavaNode();
			}
		}

		// 4. Handle "defpackage." alias prefix: e.g. "defpackage.zl0" <-> "zl0"
		if (clean.startsWith(Consts.DEFAULT_PACKAGE_NAME + ".")) {
			String withoutDef = clean.substring(Consts.DEFAULT_PACKAGE_NAME.length() + 1);
			jc = wrapper.searchJavaClassByOrigClassName(withoutDef);
			if (jc != null) return jc;
			jc = wrapper.searchJavaClassByRawName(withoutDef);
			if (jc != null) return jc;
			jc = decompiler.searchJavaClassOrItsParentByOrigFullName(withoutDef);
			if (jc != null) return jc;
			if (root != null) {
				ClassNode clsNode = root.resolveClass(ArgType.object(withoutDef));
				if (clsNode != null && clsNode.getJavaNode() != null) {
					return clsNode.getJavaNode();
				}
			}
		} else {
			String withDef = Consts.DEFAULT_PACKAGE_NAME + "." + clean;
			jc = wrapper.searchJavaClassByFullAlias(withDef);
			if (jc != null) return jc;
		}

		// 5. If it's an inner class ($), try resolving top parent class
		int dollarIdx = clean.indexOf('$');
		if (dollarIdx != -1) {
			String outer = clean.substring(0, dollarIdx);
			return findJavaClass(mainWindow, outer);
		}

		// 6. Direct scan over loaded classes in wrapper as a final fail-safe
		String canon = PatchHistoryManager.canonicalClassType(clean);
		for (JavaClass c : decompiler.getClasses()) {
			String cCanon = PatchHistoryManager.canonicalClassType(c.getRawName());
			if (cCanon.equals(canon) || PatchHistoryManager.canonicalClassType(c.getFullName()).equals(canon)
					|| c.getName().equals(clean)) {
				return c;
			}
		}

		return null;
	}

	public static boolean jumpToClassInSmali(MainWindow mainWindow, String classType) {
		return jumpToClassInSmali(mainWindow, classType, null);
	}

	public static boolean jumpToClassInSmali(MainWindow mainWindow, String classType, @Nullable Consumer<SmaliArea> onReady) {
		try {
			JavaClass jc = findJavaClass(mainWindow, classType);
			if (jc == null) {
				LOG.warn("Could not find JavaClass for classType: {}", classType);
				return false;
			}
			JClass jClass = mainWindow.getCacheObject().getNodeCache().makeFrom(jc);
			if (jClass == null) {
				return false;
			}

			ContentPanel existing = mainWindow.getTabbedPane().getTabByNode(jClass);
			if (existing instanceof ClassCodeContentPanel) {
				ClassCodeContentPanel cPanel = (ClassCodeContentPanel) existing;
				mainWindow.getTabbedPane().selectTab(cPanel);
				cPanel.showSmaliPane();
				if (cPanel.getSmaliCodeArea() instanceof SmaliArea) {
					SmaliArea sa = (SmaliArea) cPanel.getSmaliCodeArea();
					sa.requestFocus();
					if (onReady != null) {
						onReady.accept(sa);
					}
				}
				return true;
			}

			// Not yet opened: codeJump will open the tab
			mainWindow.getTabsController().codeJump(jClass);
			SwingUtilities.invokeLater(() -> {
				ContentPanel opened = mainWindow.getTabbedPane().getSelectedContentPanel();
				if (opened instanceof ClassCodeContentPanel) {
					ClassCodeContentPanel cPanel = (ClassCodeContentPanel) opened;
					cPanel.showSmaliPane();
					if (cPanel.getSmaliCodeArea() instanceof SmaliArea) {
						SmaliArea sa = (SmaliArea) cPanel.getSmaliCodeArea();
						sa.requestFocus();
						if (onReady != null) {
							onReady.accept(sa);
						}
					}
				} else {
					SwingUtilities.invokeLater(() -> {
						ContentPanel retry = mainWindow.getTabbedPane().getSelectedContentPanel();
						if (retry instanceof ClassCodeContentPanel) {
							ClassCodeContentPanel cPanel = (ClassCodeContentPanel) retry;
							cPanel.showSmaliPane();
							if (cPanel.getSmaliCodeArea() instanceof SmaliArea) {
								SmaliArea sa = (SmaliArea) cPanel.getSmaliCodeArea();
								sa.requestFocus();
								if (onReady != null) {
									onReady.accept(sa);
								}
							}
						}
					});
				}
			});
			return true;
		} catch (Exception e) {
			LOG.error("Failed to jump to class: {}", classType, e);
		}
		return false;
	}

	private void showDiffForSelectedCheckpoint() {
		int row = checkpointsTable.getSelectedRow();
		if (row < 0 || row >= currentHistory.size() || currentSelectedClass == null) {
			JOptionPane.showMessageDialog(this, "Please select a checkpoint from the list.", "Diff", JOptionPane.WARNING_MESSAGE);
			return;
		}

		PatchCommit selected = currentHistory.get(row);
		if (selected.isMilestone() && row < currentHistory.size() - 1) {
			PatchCommit parent = currentHistory.get(row + 1);
			String parentSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(currentSelectedClass, parent.getFullHash());
			String selectedSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(currentSelectedClass, selected.getFullHash());
			if (selectedSmali != null && selectedSmali.equals(parentSmali)) {
				JOptionPane.showMessageDialog(this,
						"This checkpoint is a milestone event (" + selected.getMessage() + ") and contains no code changes.",
						"Milestone Checkpoint", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
		}
		String selectedSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(currentSelectedClass, selected.getFullHash());
		if (selectedSmali == null) {
			JOptionPane.showMessageDialog(this, "Could not load smali for selected checkpoint.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		String leftCode;
		String leftTitle;
		String rightCode;
		String rightTitle;
		String dialogTitle;

		if (row < currentHistory.size() - 1) {
			PatchCommit parent = currentHistory.get(row + 1);
			String parentSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(currentSelectedClass, parent.getFullHash());
			if (parentSmali != null) {
				leftCode = parentSmali;
				leftTitle = "Before: " + parent.getShortHash() + " (" + parent.getFormattedTime() + ")";
				rightCode = selectedSmali;
				rightTitle = "After: " + selected.getShortHash() + " (" + selected.getFormattedTime() + ")";
				dialogTitle = "Changes in Checkpoint " + selected.getShortHash() + " (vs " + parent.getShortHash() + ")";
			} else {
				leftCode = "";
				leftTitle = "None";
				rightCode = selectedSmali;
				rightTitle = "Checkpoint: " + selected.getShortHash();
				dialogTitle = "Checkpoint " + selected.getShortHash();
			}
		} else {
			leftCode = "";
			leftTitle = "Initial Baseline";
			rightCode = selectedSmali;
			rightTitle = "Checkpoint: " + selected.getShortHash() + " (" + selected.getFormattedTime() + ")";
			dialogTitle = "Checkpoint " + selected.getShortHash() + " (Initial Baseline)";
		}

		PatchDiffDialog diffDialog = new PatchDiffDialog(
				mainWindow, currentSelectedClass, dialogTitle,
				leftCode, leftTitle,
				rightCode, rightTitle,
				() -> performRollbackToCode(currentSelectedClass, selectedSmali, selected)
		);
		diffDialog.setVisible(true);
	}

	private void showDiffWithCurrent() {
		int row = checkpointsTable.getSelectedRow();
		if (row < 0 || row >= currentHistory.size() || currentSelectedClass == null) {
			JOptionPane.showMessageDialog(this, "Please select a checkpoint from the list.", "Diff", JOptionPane.WARNING_MESSAGE);
			return;
		}

		PatchCommit selected = currentHistory.get(row);
		String selectedSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(currentSelectedClass, selected.getFullHash());
		if (selectedSmali == null) {
			JOptionPane.showMessageDialog(this, "Could not load smali for selected checkpoint.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		// Read current active Smali (either from active SmaliArea if open, or latest from Git HEAD)
		String currentSmali = getCurrentActiveSmali(currentSelectedClass);

		PatchDiffDialog diffDialog = new PatchDiffDialog(
				mainWindow, currentSelectedClass,
				"Diff: Checkpoint " + selected.getShortHash() + " vs Current Active",
				selectedSmali, "Checkpoint: " + selected.getShortHash() + " (" + selected.getFormattedTime() + ")",
				currentSmali, "Current Active Version",
				() -> performRollbackToCode(currentSelectedClass, selectedSmali, selected)
		);
		diffDialog.setVisible(true);
	}

	private String getCurrentActiveSmali(String classType) {
		String targetCanon = PatchHistoryManager.canonicalClassType(classType);
		// Check currently selected tab
		ContentPanel panel = mainWindow.getTabbedPane().getSelectedContentPanel();
		if (panel instanceof ClassCodeContentPanel) {
			ClassCodeContentPanel cPanel = (ClassCodeContentPanel) panel;
			if (cPanel.getSmaliCodeArea() instanceof SmaliArea) {
				SmaliArea sa = (SmaliArea) cPanel.getSmaliCodeArea();
				if (sa.getJClass() != null && PatchHistoryManager.canonicalClassType(sa.getJClass().getFullName()).equals(targetCanon)) {
					return sa.getText();
				}
			}
		}
		// Also check any open tab in tabbedPane
		JavaClass jc = findJavaClass(mainWindow, classType);
		if (jc != null) {
			JClass jClass = mainWindow.getCacheObject().getNodeCache().makeFrom(jc);
			if (jClass != null) {
				ContentPanel openTab = mainWindow.getTabbedPane().getTabByNode(jClass);
				if (openTab instanceof ClassCodeContentPanel) {
					ClassCodeContentPanel cPanel = (ClassCodeContentPanel) openTab;
					if (cPanel.getSmaliCodeArea() instanceof SmaliArea) {
						return ((SmaliArea) cPanel.getSmaliCodeArea()).getText();
					}
				}
			}
		}
		// Fallback: read latest from Git HEAD
		if (!currentHistory.isEmpty()) {
			String headSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(classType, currentHistory.get(0).getFullHash());
			if (headSmali != null) {
				return headSmali;
			}
		}
		return "";
	}

	private void rollbackSelectedCheckpoint() {
		int row = checkpointsTable.getSelectedRow();
		if (row < 0 || row >= currentHistory.size() || currentSelectedClass == null) {
			JOptionPane.showMessageDialog(this, "Please select a checkpoint to rollback to.", "Rollback", JOptionPane.WARNING_MESSAGE);
			return;
		}
		PatchCommit selected = currentHistory.get(row);
		int confirm = JOptionPane.showConfirmDialog(this,
				"Are you sure you want to rollback " + currentSelectedClass + " to checkpoint:\n["
						+ selected.getShortHash() + "] " + selected.getMessage() + "?",
				"Confirm Rollback",
				JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}

		String historicalSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(currentSelectedClass, selected.getFullHash());
		if (historicalSmali != null) {
			performRollbackToCode(currentSelectedClass, historicalSmali, selected);
		}
	}

	private void performRollbackToCode(String classType, String targetSmali, PatchCommit commit) {
		boolean jumped = jumpToClassInSmali(mainWindow, classType, sa -> {
			sa.applySmaliCode(targetSmali, "Rollback to [" + commit.getShortHash() + "] " + commit.getMessage());
			JOptionPane.showMessageDialog(mainWindow,
					"Successfully rolled back " + classType + " to checkpoint [" + commit.getShortHash() + "].",
					"Rollback Complete", JOptionPane.INFORMATION_MESSAGE);
		});
		if (!jumped) {
			JOptionPane.showMessageDialog(this, "Could not open class " + classType + " for rollback.", "Rollback Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		dispose();
	}

	private static String getShortClassName(String fullClass) {
		if (fullClass == null) {
			return "";
		}
		int lastDot = fullClass.lastIndexOf('.');
		return (lastDot >= 0 && lastDot < fullClass.length() - 1) ? fullClass.substring(lastDot + 1) : fullClass;
	}
}
