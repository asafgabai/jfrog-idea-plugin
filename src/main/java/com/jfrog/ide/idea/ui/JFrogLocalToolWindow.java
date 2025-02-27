package com.jfrog.ide.idea.ui;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.Constraints;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.impl.jdkDownloader.RuntimeChooserUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.*;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.util.ui.UIUtil;
import com.jfrog.ide.common.nodes.*;
import com.jfrog.ide.idea.actions.CollapseAllAction;
import com.jfrog.ide.idea.actions.ExpandAllAction;
import com.jfrog.ide.idea.actions.GoToSettingsAction;
import com.jfrog.ide.idea.actions.ScanTimeLabelAction;
import com.jfrog.ide.idea.configuration.GlobalSettings;
import com.jfrog.ide.idea.events.ApplicationEvents;
import com.jfrog.ide.idea.log.Logger;
import com.jfrog.ide.idea.scan.ScanManager;
import com.jfrog.ide.idea.ui.jcef.message.MessageType;
import com.jfrog.ide.idea.ui.utils.ComponentUtils;
import com.jfrog.ide.idea.ui.webview.WebviewManager;
import com.jfrog.ide.idea.ui.webview.WebviewObjectConverter;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.cef.browser.CefBrowser;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import static com.jfrog.ide.idea.ui.JFrogToolWindow.SCROLL_BAR_SCROLLING_UNITS;

/**
 * @author yahavi
 */
public class JFrogLocalToolWindow extends AbstractJFrogToolWindow {
    private final LocalComponentsTree componentsTree;
    private final OnePixelSplitter verticalSplit;
    private JPanel leftPanelContent;
    private JComponent compTreeView;
    private boolean isInitialized;
    private IssueNode selectedIssue;
    private WebviewManager webviewManager;

    /**
     * @param project - Currently opened IntelliJ project
     */
    public JFrogLocalToolWindow(@NotNull Project project) {
        super(project);
        componentsTree = LocalComponentsTree.getInstance(project);
        JPanel leftPanel = new JBPanel<>(new BorderLayout());
        verticalSplit = new OnePixelSplitter(false, 0.4f);
        verticalSplit.setFirstComponent(leftPanel);
        setContent(verticalSplit);
        if (!JBCefApp.isSupported()) {
            leftPanel.add(createJcefNotSupportedView(), 0);
            return;
        }

        JComponent browserComponent;
        try {
            browserComponent = initVulnerabilityInfoBrowser();
        } catch (IOException | URISyntaxException e) {
            Logger.getInstance().error("Local view couldn't be initialized.", e);
            leftPanel.removeAll();
            leftPanel.add(createLoadErrorView(), 0);
            return;
        }

        JPanel toolbar = createActionToolbar();
        toolbar.setBorder(IdeBorderFactory.createBorder(SideBorder.BOTTOM));
        leftPanel.add(toolbar, BorderLayout.PAGE_START);
        leftPanelContent = new JBPanel<>(new BorderLayout());
        leftPanel.add(leftPanelContent);
        compTreeView = createComponentsTreeView();

        alertIfCacheExpired();
        refreshView();
        registerListeners(browserComponent);
        isInitialized = true;
    }

    @Override
    public JPanel createActionToolbar() {
        DefaultActionGroup actionGroup = new DefaultActionGroup(new CollapseAllAction(componentsTree), new ExpandAllAction(componentsTree),
                new GoToSettingsAction(), new Separator(), new ScanTimeLabelAction());
        actionGroup.addAction(ActionManager.getInstance().getAction("JFrog.StartLocalScan"), Constraints.FIRST);
        return createJFrogToolbar(actionGroup);
    }

    /**
     * Register the issues tree listeners.
     */
    public void registerListeners(JComponent browserComponent) {
        // Xray credentials were set listener
        appBusConnection.subscribe(ApplicationEvents.ON_CONFIGURATION_DETAILS_CHANGE, (ApplicationEvents) () -> ApplicationManager.getApplication().invokeLater(this::onConfigurationChange));

        // Wrap the browser component in a Panel to avoid display issues that may occur in some versions of IntelliJ in Windows.
        JPanel browserWrapper = new JBPanel<>();
        browserWrapper.setLayout(new GridLayout());
        browserWrapper.add(browserComponent);

        // Component selection listener
        componentsTree.addTreeSelectionListener(e -> {
            if (e == null || e.getNewLeadSelectionPath() == null || !(e.getNewLeadSelectionPath().getLastPathComponent() instanceof IssueNode)) {
                verticalSplit.setSecondComponent(null);
                return;
            }

            selectedIssue = (IssueNode) e.getNewLeadSelectionPath().getLastPathComponent();
            updateIssueOrLicenseInWebview(selectedIssue);
            verticalSplit.setSecondComponent(browserWrapper);
        });
        projectBusConnection.subscribe(ApplicationEvents.ON_SCAN_LOCAL_STARTED, (ApplicationEvents) () -> {
            setLeftPanelContent(compTreeView);
            ApplicationManager.getApplication().invokeLater(this::resetViews);
        });
        componentsTree.addRightClickListener();
    }

    private void alertIfCacheExpired() {
        if (!componentsTree.isCacheEmpty() && componentsTree.isCacheExpired()) {
            Logger.showActionableBalloon(project,
                    "The scan results have expired.\nClick <a href=\"here\">here</a> to trigger a scan.", () ->
                            ScanManager.getInstance(project).startScan());
        }
    }

    private void refreshView() {
        if (!GlobalSettings.getInstance().reloadXrayCredentials()) {
            setLeftPanelContent(ComponentUtils.createNoCredentialsView());
            return;
        }
        if (componentsTree.isCacheEmpty() && !ScanManager.getInstance(project).isScanInProgress()) {
            setLeftPanelContent(createReadyEnvView());
            return;
        }
        setLeftPanelContent(compTreeView);
    }

    private JComponent createReadyEnvView() {
        JPanel readyEnvPanel = new JBPanel<>();
        readyEnvPanel.setLayout(new BoxLayout(readyEnvPanel, BoxLayout.PAGE_AXIS));

        // "We're all set!"
        JBLabel allSetLabel = new JBLabel();
        allSetLabel.setText("We're all set.");
        ComponentUtils.addCenteredComponent(readyEnvPanel, allSetLabel);

        // "Scan your project"
        HyperlinkLabel scanLink = new HyperlinkLabel();
        scanLink.setTextWithHyperlink("<hyperlink>Scan your project</hyperlink>");
        scanLink.addHyperlinkListener(e -> ScanManager.getInstance(project).startScan());
        ComponentUtils.addCenteredComponent(readyEnvPanel, scanLink);

        return ComponentUtils.createUnsupportedPanel(readyEnvPanel);
    }

    private JComponent createJcefNotSupportedView() {
        JPanel jcefNotSupportedPanel = new JBPanel<>();
        jcefNotSupportedPanel.setLayout(new BoxLayout(jcefNotSupportedPanel, BoxLayout.PAGE_AXIS));

        // "Thank you for installing the JFrog IDEA Plugin!"
        JBLabel thanksLabel = new JBLabel();
        thanksLabel.setText("Thank you for installing the JFrog IntelliJ IDEA Plugin!");
        ComponentUtils.addCenteredComponent(jcefNotSupportedPanel, thanksLabel);

        // "The plugin uses a component named JCEF that seem to be missing in your IDE."
        JBLabel pluginNeedsJcefLabel = new JBLabel();
        pluginNeedsJcefLabel.setText("The plugin uses a component named JCEF that seem to be missing in your IDE.");
        ComponentUtils.addCenteredComponent(jcefNotSupportedPanel, pluginNeedsJcefLabel);

        // "To make JCEF available in your IDE, you’ll need to have the IDE use a different boot runtime."
        JBLabel replaceBootRuntimeLabel = new JBLabel();
        replaceBootRuntimeLabel.setText("To make JCEF available in your IDE, you’ll need to have the IDE use a different boot runtime.");
        ComponentUtils.addCenteredComponent(jcefNotSupportedPanel, replaceBootRuntimeLabel);

        // "Click here, choose a boot runtime with JCEF, restart the IDE and come back."
        HyperlinkLabel scanLink = new HyperlinkLabel();
        scanLink.setTextWithHyperlink("<hyperlink>Click here</hyperlink>, choose a boot runtime with JCEF, restart the IDE and come back.");
        scanLink.addHyperlinkListener(e -> RuntimeChooserUtil.INSTANCE.showRuntimeChooserPopup());
        ComponentUtils.addCenteredComponent(jcefNotSupportedPanel, scanLink);

        return ComponentUtils.createUnsupportedPanel(jcefNotSupportedPanel);
    }

    private JComponent createLoadErrorView() {
        JPanel loadErrorPanel = new JBPanel<>();
        loadErrorPanel.setLayout(new BoxLayout(loadErrorPanel, BoxLayout.PAGE_AXIS));

        // "The view couldn't be loaded."
        JBLabel viewNotLoadedLabel = new JBLabel();
        viewNotLoadedLabel.setText("The view couldn't be loaded.");
        ComponentUtils.addCenteredComponent(loadErrorPanel, viewNotLoadedLabel);

        // "Check the Notifications / Event Log for more information."
        JBLabel checkLogsLabel = new JBLabel();
        checkLogsLabel.setText("Check the Notifications / Event Log for more information.");
        ComponentUtils.addCenteredComponent(loadErrorPanel, checkLogsLabel);

        return ComponentUtils.createUnsupportedPanel(loadErrorPanel);
    }

    private void setLeftPanelContent(JComponent component) {
        leftPanelContent.removeAll();
        leftPanelContent.add(component, 0);
    }

    private JComponent initVulnerabilityInfoBrowser() throws IOException, URISyntaxException {
        webviewManager = new WebviewManager();
        Disposer.register(this, webviewManager);

        CefBrowser browser = webviewManager.createBrowser(() -> updateIssueOrLicenseInWebview(selectedIssue));
        return (JComponent) browser.getUIComponent();
    }

    /**
     * Create the components tree panel.
     *
     * @return the components tree panel
     */
    private JComponent createComponentsTreeView() {
        JPanel treePanel = new JBPanel<>(new GridLayout()).withBackground(UIUtil.getTableBackground());
        TreeSpeedSearch treeSpeedSearch = new TreeSpeedSearch(componentsTree, true, ComponentUtils::getPathSearchString);
        treeSpeedSearch.getComponent().getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        treePanel.add(treeSpeedSearch.getComponent(), BorderLayout.WEST);
        JScrollPane treeScrollPane = ScrollPaneFactory.createScrollPane(treePanel);
        treeScrollPane.getVerticalScrollBar().setUnitIncrement(SCROLL_BAR_SCROLLING_UNITS);
        treeScrollPane.setBorder(null);
        return treeScrollPane;
    }

    private void updateIssueOrLicenseInWebview(IssueNode issueNode) {
        if (issueNode instanceof VulnerabilityNode issue) {
            webviewManager.sendMessage(MessageType.SHOW_PAGE, WebviewObjectConverter.convertIssueToDepPage(issue));
        } else if (issueNode instanceof ApplicableIssueNode) {
            ApplicableIssueNode node = (ApplicableIssueNode) issueNode;
            webviewManager.sendMessage(MessageType.SHOW_PAGE, WebviewObjectConverter.convertIssueToDepPage(node.getIssue()));
            navigateToFile(node);
        } else if (issueNode instanceof LicenseViolationNode license) {
            webviewManager.sendMessage(MessageType.SHOW_PAGE, WebviewObjectConverter.convertLicenseToDepPage(license));
        } else if (issueNode instanceof FileIssueNode node) {
            webviewManager.sendMessage(MessageType.SHOW_PAGE, WebviewObjectConverter.convertFileIssueToIssuePage(node));
            navigateToFile(node);
        }
    }

    private void navigateToFile(FileIssueNode node) {
        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile sourceCodeFile = LocalFileSystem.getInstance().findFileByIoFile(new File(node.getFilePath()));
            if (sourceCodeFile == null) {
                return;
            }
            PsiFile targetFile = PsiManager.getInstance(project).findFile(sourceCodeFile);
            if (targetFile == null) {
                return;
            }
            int lineOffset = StringUtil.lineColToOffset(targetFile.getText(), node.getRowStart(), node.getColStart());
            PsiElement element = targetFile.findElementAt(lineOffset);
            if (element instanceof Navigatable) {
                ((Navigatable) element).navigate(true);
            } else targetFile.navigate(true);
        });
    }

    /**
     * Clear the component tree.
     */
    void resetViews() {
        if (componentsTree != null) {
            componentsTree.reset();
        }
    }

    /**
     * Called after a change in the credentials.
     */
    @Override
    public void onConfigurationChange() {
        if (componentsTree != null) {
            try {
                componentsTree.deleteCachedTree();
            } catch (IOException e) {
                Logger.getInstance().warn("An error occurred while trying to delete the scan results cache: " + ExceptionUtils.getRootCauseMessage(e));
            }
        }
        super.onConfigurationChange();
        refreshView();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (isInitialized) {
            refreshView();
        }
    }
}
