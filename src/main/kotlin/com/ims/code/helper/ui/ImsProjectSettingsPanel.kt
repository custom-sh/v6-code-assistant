package com.ims.code.helper.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.ims.code.helper.util.ImsBundle
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel
import com.intellij.ui.table.JBTable

/**
 * 统一配置面板 UI
 * 语言切换：下拉框选完即实时刷新面板文本；点击 Apply 持久化到全局设置
 */
class ImsProjectSettingsPanel(private val project: Project) {

    private val languageCombo = JComboBox(DefaultComboBoxModel(arrayOf("zh", "en", "ide")))
    private val projectCodeField = JBTextField(30)
    private val projectNameField = JBTextField(30)
    private val serverPathField = TextFieldWithBrowseButton()
    private val webPathField = TextFieldWithBrowseButton()
    private val pdaPathField = TextFieldWithBrowseButton()
    private val serverPathHint = JBLabel(" ")
    private val webPathHint = JBLabel(" ")
    private val pdaPathHint = JBLabel(" ")

    // Server 端路径操作按钮
    private val serverUpgradeBtn = JButton()
    private val serverBuildBtn = JButton()
    // Web 端路径操作按钮
    private val webUpgradeBtn = JButton()
    private val webBuildBtn = JButton()
    // PDA 端路径操作按钮
    private val pdaUpgradeBtn = JButton()
    private val pdaBuildBtn = JButton()
    private val aiProviderCombo = JComboBox(DefaultComboBoxModel(arrayOf("OpenAI", "Claude", "DeepSeek", "Custom")))
    private val aiModelField = JBTextField(30)
    private val apiKeyField = JBPasswordField()
    private val apiEndpointField = JBTextField(30)
    private val extraConfigModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val extraConfigTable = JBTable(extraConfigModel)
    private val addBtn = JButton("+")
    private val removeBtn = JButton("-")
    private val initProjectBtn = JButton().apply { icon = com.intellij.icons.AllIcons.Actions.Execute }

    // 需要随语言实时刷新的标签 -> 资源 key
    private val refreshable = mutableListOf<Pair<JBLabel, String>>()
    private var rootComponent: JComponent? = null

    /** 创建路径行：带浏览按钮的输入框（自动撑满） + 升级按钮 + 构建按钮 */
    private fun createPathRow(pathField: TextFieldWithBrowseButton, upgradeBtn: JButton, buildBtn: JButton): JComponent {
        val row = JPanel(java.awt.GridBagLayout())
        row.isOpaque = false
        val gbc = java.awt.GridBagConstraints().apply {
            gridy = 0; fill = java.awt.GridBagConstraints.HORIZONTAL; weighty = 0.0; insets = java.awt.Insets(0, 0, 0, 0)
        }
        // 输入框：撑满剩余宽度
        gbc.gridx = 0; gbc.weightx = 1.0
        row.add(pathField, gbc)
        // 升级按钮：固定宽度
        gbc.gridx = 1; gbc.weightx = 0.0; gbc.insets = java.awt.Insets(0, 6, 0, 0)
        row.add(upgradeBtn, gbc)
        // 构建按钮：固定宽度
        gbc.gridx = 2; gbc.weightx = 0.0; gbc.insets = java.awt.Insets(0, 4, 0, 0)
        row.add(buildBtn, gbc)
        return row
    }

    fun createPanel(): JComponent {
        setupListeners()
        setupLanguageRenderer()

        val extraPanel = JPanel(BorderLayout())
        extraPanel.add(extraConfigTable, BorderLayout.CENTER)
        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        btnPanel.add(addBtn); btnPanel.add(removeBtn)
        extraPanel.add(btnPanel, BorderLayout.SOUTH)

        refreshable.clear()

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("🌐 语言 / Language:", languageCombo)
            .addSeparator()
            .addLabeledComponent(label("settings.project.code"), projectCodeRow())
            .addLabeledComponent(label("settings.project.name"), projectNameRow())
            .addSeparator()
            .addLabeledComponent(label("settings.path.server"), createPathRow(serverPathField, serverUpgradeBtn, serverBuildBtn))
            .addLabeledComponent(label("settings.path.web"), createPathRow(webPathField, webUpgradeBtn, webBuildBtn))
            .addLabeledComponent(label("settings.path.pda"), createPathRow(pdaPathField, pdaUpgradeBtn, pdaBuildBtn))
            .addSeparator()
            .addLabeledComponent(label("settings.ai.provider"), aiProviderCombo)
            .addLabeledComponent(label("settings.ai.model"), aiModelField)
            .addLabeledComponent(label("settings.ai.api.key"), apiKeyField)
            .addLabeledComponent(label("settings.ai.api.endpoint"), apiEndpointField)
            .addSeparator()
            .addLabeledComponent(label("settings.extra.title"), extraPanel)
            .panel

        // 用 BorderLayout 把表单钉在顶部，避免被纵向居中导致语言行上方出现空白
        val wrapper = JPanel(BorderLayout())
        wrapper.border = com.intellij.util.ui.JBUI.Borders.empty(10)
        wrapper.add(form, BorderLayout.NORTH)

        rootComponent = wrapper
        refreshTexts()
        return wrapper
    }

    /** 创建一个随语言刷新的标签，并登记到刷新列表 */
    private fun label(key: String): JBLabel {
        val l = JBLabel()
        refreshable += l to key
        return l
    }

    /** 项目编码行（仅输入框，固定列宽，与项目名称等宽） */
    private fun projectCodeRow(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.add(projectCodeField)
        return row
    }

    /** 项目名称输入框 + 「初始化项目模板」按钮（输入框撑满、按钮固定宽度） */
    private fun projectNameRow(): JComponent {
        val row = JPanel(java.awt.GridBagLayout())
        val gbc = java.awt.GridBagConstraints().apply {
            gridy = 0; fill = java.awt.GridBagConstraints.HORIZONTAL; insets = java.awt.Insets(0, 0, 0, 0)
        }
        gbc.gridx = 0; gbc.weightx = 1.0
        row.add(projectNameField, gbc)
        gbc.gridx = 1; gbc.weightx = 0.0; gbc.insets = java.awt.Insets(0, 8, 0, 0)
        row.add(initProjectBtn, gbc)
        updateInitButtonState()
        return row
    }

    /** 项目编码、项目名称、三个路径全部非空时，「初始化项目模板」按钮才可点击 */
    private fun updateInitButtonState() {
        initProjectBtn.isEnabled = projectCodeField.text.isNotBlank() &&
                projectNameField.text.isNotBlank() &&
                serverPathField.text.isNotBlank() &&
                webPathField.text.isNotBlank() &&
                pdaPathField.text.isNotBlank()
    }

    /** 按下拉框当前选中的语言（尚未保存）实时刷新所有标签文本 */
    private fun refreshTexts() {
        val lang = currentLang()
        for ((lbl, key) in refreshable) {
            lbl.text = ImsBundle.messageWith(lang, key)
        }
        initProjectBtn.text = ImsBundle.messageWith(lang, "settings.project.init.button")
        // 路径操作按钮
        serverUpgradeBtn.text = ImsBundle.messageWith(lang, "settings.path.upgrade")
        serverBuildBtn.text = ImsBundle.messageWith(lang, "settings.path.build")
        webUpgradeBtn.text = ImsBundle.messageWith(lang, "settings.path.upgrade")
        webBuildBtn.text = ImsBundle.messageWith(lang, "settings.path.build")
        pdaUpgradeBtn.text = ImsBundle.messageWith(lang, "settings.path.upgrade")
        pdaBuildBtn.text = ImsBundle.messageWith(lang, "settings.path.build")
        // 路径校验提示也随语言刷新（重跑校验，按当前选中语言生成文案）
        validatePath(serverPathField, serverPathHint)
        validatePath(webPathField, webPathHint)
        validatePath(pdaPathField, pdaPathHint)
        rootComponent?.revalidate()
        rootComponent?.repaint()
    }

    private fun currentLang(): String = languageCombo.selectedItem as? String ?: "ide"

    private fun setupLanguageRenderer() {
        languageCombo.renderer = javax.swing.ListCellRenderer { _, value, _, _, _ ->
            javax.swing.JLabel(when (value) {
                "ide" -> "Auto/跟随IDE"
                "zh" -> "中文"
                "en" -> "English"
                else -> value ?: ""
            })
        }
    }

    /** 把三个回调都指向同一个动作的 DocumentListener 工具 */
    private fun simpleDocListener(action: () -> Unit) = object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = action()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = action()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = action()
    }

    private fun setupListeners() {
        for ((field, hint) in listOf(serverPathField to serverPathHint, webPathField to webPathHint, pdaPathField to pdaPathHint)) {
            field.addActionListener {
                val dir = chooseDirectory(field.text)
                if (dir != null) {
                    field.text = dir.absolutePath
                    // 行为 B：以所选目录的父级为参照，精确补齐仍为空的 server/web/pda
                    dir.parentFile?.let { autoFillModules(it) }
                }
            }
            field.textField.document.addDocumentListener(simpleDocListener {
                validatePath(field, hint); updateInitButtonState()
            })
        }

        languageCombo.addActionListener { refreshTexts() }

        initProjectBtn.addActionListener { onInitProject() }
        // 路径操作按钮（空实现，后续阶段扩展）
        serverUpgradeBtn.addActionListener { /* TODO: Server 升级 */ }
        serverBuildBtn.addActionListener { /* TODO: Server 构建 */ }
        webUpgradeBtn.addActionListener { /* TODO: Web 升级 */ }
        webBuildBtn.addActionListener { /* TODO: Web 构建 */ }
        pdaUpgradeBtn.addActionListener { /* TODO: PDA 升级 */ }
        pdaBuildBtn.addActionListener { /* TODO: PDA 构建 */ }
        // 项目编码 / 项目名称变化时刷新「初始化项目模板」按钮可用状态
        projectCodeField.document.addDocumentListener(simpleDocListener { updateInitButtonState() })
        projectNameField.document.addDocumentListener(simpleDocListener { updateInitButtonState() })

        aiProviderCombo.addActionListener { updateProviderDefaults() }

        addBtn.addActionListener { extraConfigModel.addRow(arrayOf("", "")) }
        removeBtn.addActionListener {
            val row = extraConfigTable.selectedRow
            if (row >= 0) extraConfigModel.removeRow(row)
        }
    }

    /**
     * 行为 A：若当前 IDEA 打开了项目，定位到真正包含 server/web/pda 的目录并精确填充；
     * 由 Configurable.reset() 在回填持久化值之后调用——只填空、找不到留空。
     */
    fun autoFillFromOpenProject() {
        val root = project.basePath?.let { File(it) } ?: return
        val container = findModuleContainer(root) ?: return
        autoFillModules(container)
    }

    /**
     * 在项目根附近定位"含有 server/web/pda 子目录"的那一层（命中数最多者）。
     * 有界递归：限制深度、跳过 .git/node_modules/build 等重目录；
     * 同时兼顾"打开的项目本身就是某个模块"——去其同级目录找。
     */
    private fun findModuleContainer(root: File, maxDepth: Int = 3): File? {
        fun score(dir: File): Int {
            val children = dir.listFiles()?.filter { it.isDirectory } ?: return 0
            return MODULE_NAMES.count { name -> children.any { it.name.equals(name, ignoreCase = true) } }
        }

        var best: File? = null
        var bestScore = 0
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val s = score(dir)
            if (s > bestScore) { bestScore = s; best = dir }
            if (bestScore == MODULE_NAMES.size) break // 已全部命中，提前结束
            if (depth < maxDepth) {
                dir.listFiles()
                    ?.filter { it.isDirectory && it.name !in SKIP_DIRS && !it.name.startsWith(".") }
                    ?.forEach { queue.add(it to depth + 1) }
            }
        }

        // 打开的项目可能就是某个模块（如直接打开 server），此时同级目录里有其它模块
        root.parentFile?.let { parent ->
            val s = score(parent)
            if (s > bestScore) { bestScore = s; best = parent }
        }

        return best
    }

    /** 在 baseDir 下精确查找同名的 server/web/pda 目录，只填当前为空的字段，填完刷新校验 */
    private fun autoFillModules(baseDir: File) {
        if (!baseDir.isDirectory) return
        fillIfEmpty(serverPathField, File(baseDir, "server"))
        fillIfEmpty(webPathField, File(baseDir, "web"))
        fillIfEmpty(pdaPathField, File(baseDir, "pda"))
        validatePath(serverPathField, serverPathHint)
        validatePath(webPathField, webPathHint)
        validatePath(pdaPathField, pdaPathHint)
    }

    /** 字段为空且目标目录存在时才填，避免覆盖已有值 */
    private fun fillIfEmpty(field: TextFieldWithBrowseButton, dir: File) {
        if (field.text.isBlank() && dir.isDirectory) {
            field.text = dir.absolutePath
        }
    }

    private fun validatePath(field: TextFieldWithBrowseButton, hint: JBLabel) {
        val path = field.text.trim()
        if (path.isEmpty()) { hint.text = " "; return }
        val lang = currentLang()
        val dir = File(path)
        when {
            !dir.exists() -> { hint.text = ImsBundle.messageWith(lang, "settings.path.not.exist"); hint.foreground = com.intellij.util.ui.UIUtil.getErrorForeground() }
            !dir.isDirectory -> { hint.text = ImsBundle.messageWith(lang, "settings.path.not.directory"); hint.foreground = com.intellij.util.ui.UIUtil.getErrorForeground() }
            else -> { hint.text = ImsBundle.messageWith(lang, "settings.path.valid"); hint.foreground = com.intellij.util.ui.UIUtil.getLabelForeground() }
        }
    }

    /** 点击「初始化项目模板」：弹出确认框（文案随当前选中语言切换），确认后的逻辑暂留空 */
    private fun onInitProject() {
        val lang = currentLang()

        // 点击时校验三个路径是否合法有效（存在且为目录），无效则提示并中止
        val invalid = listOf(
            "settings.path.server" to serverPathField.text.trim(),
            "settings.path.web" to webPathField.text.trim(),
            "settings.path.pda" to pdaPathField.text.trim()
        ).filter { (_, p) -> !File(p).isDirectory }

        if (invalid.isNotEmpty()) {
            val detail = invalid.joinToString("\n") { (key, p) ->
                "- ${ImsBundle.messageWith(lang, key)} $p"
            }
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                ImsBundle.messageWith(lang, "settings.project.init.invalid.path") + "\n" + detail,
                ImsBundle.messageWith(lang, "settings.project.init.title")
            )
            return
        }

        // 索引未就绪时重构不可用，提示用户等待
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                ImsBundle.messageWith(lang, "init.error.index.not.ready"),
                ImsBundle.messageWith(lang, "settings.project.init.title")
            )
            return
        }

        val answer = com.intellij.openapi.ui.Messages.showDialog(
            project,
            ImsBundle.messageWith(lang, "settings.project.init.confirm"),
            ImsBundle.messageWith(lang, "settings.project.init.title"),
            arrayOf(
                ImsBundle.messageWith(lang, "settings.project.init.yes"),
                ImsBundle.messageWith(lang, "settings.project.init.no")
            ),
            0,  // 默认选中"确定"
            com.intellij.openapi.ui.Messages.getQuestionIcon()
        )
        if (answer == 0) {
            // 将当前面板值应用到 settings（即使用户尚未点击 Apply）
            val settings = com.ims.code.helper.config.ImsProjectSettings.getInstance(project).apply {
                projectCode = this@ImsProjectSettingsPanel.getProjectCode()
                serverPath = this@ImsProjectSettingsPanel.getServerPath()
                webPath = this@ImsProjectSettingsPanel.getWebPath()
                pdaPath = this@ImsProjectSettingsPanel.getPdaPath()
            }
            com.ims.code.helper.generator.ProjectInitializer.initializeProject(project, settings)
        }
    }

    private fun updateProviderDefaults() {
        when (aiProviderCombo.selectedItem) {
            "OpenAI" -> { if (apiEndpointField.text.isEmpty()) apiEndpointField.text = "https://api.openai.com/v1"; if (aiModelField.text.isEmpty()) aiModelField.text = "gpt-4" }
            "Claude" -> { if (apiEndpointField.text.isEmpty()) apiEndpointField.text = "https://api.anthropic.com/v1"; if (aiModelField.text.isEmpty()) aiModelField.text = "claude-sonnet-4-6" }
            "DeepSeek" -> { if (apiEndpointField.text.isEmpty()) apiEndpointField.text = "https://api.deepseek.com/v1"; if (aiModelField.text.isEmpty()) aiModelField.text = "deepseek-chat" }
        }
    }

    private fun chooseDirectory(currentPath: String): File? {
        val chooser = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor()
        chooser.title = ImsBundle.message("file.chooser.title")
        val virtualFile = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
            chooser, project,
            if (currentPath.isNotEmpty()) com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(currentPath) else null
        )
        return virtualFile?.let { File(it.path) }
    }

    // === Getter / Setter ===
    fun getLanguage(): String = languageCombo.selectedItem as String
    fun setLanguage(value: String) { languageCombo.selectedItem = value }

    fun getProjectCode(): String = projectCodeField.text.trim()
    fun setProjectCode(value: String) { projectCodeField.text = value }

    fun getProjectName(): String = projectNameField.text.trim()
    fun setProjectName(value: String) { projectNameField.text = value }

    fun getServerPath(): String = serverPathField.text.trim()
    fun setServerPath(value: String) { serverPathField.text = value }

    fun getWebPath(): String = webPathField.text.trim()
    fun setWebPath(value: String) { webPathField.text = value }

    fun getPdaPath(): String = pdaPathField.text.trim()
    fun setPdaPath(value: String) { pdaPathField.text = value }

    fun getAiProvider(): String = aiProviderCombo.selectedItem as String
    fun setAiProvider(value: String) { aiProviderCombo.selectedItem = value }

    fun getAiModel(): String = aiModelField.text.trim()
    fun setAiModel(value: String) { aiModelField.text = value }

    fun getApiKey(): String = String(apiKeyField.password)
    fun setApiKey(value: String) { apiKeyField.text = value }

    fun getApiEndpoint(): String = apiEndpointField.text.trim()
    fun setApiEndpoint(value: String) { apiEndpointField.text = value }

    fun getExtraConfig(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (i in 0 until extraConfigModel.rowCount) {
            val key = extraConfigModel.getValueAt(i, 0) as? String ?: continue
            val value = extraConfigModel.getValueAt(i, 1) as? String ?: continue
            if (key.isNotBlank()) result[key.trim()] = value.trim()
        }
        return result
    }

    fun setExtraConfig(config: Map<String, String>) {
        extraConfigModel.rowCount = 0
        config.forEach { (k, v) -> extraConfigModel.addRow(arrayOf(k, v)) }
    }

    companion object {
        // 仅精确识别这三个模块目录名
        private val MODULE_NAMES = listOf("server", "web", "pda")
        // 递归查找时跳过的重目录
        private val SKIP_DIRS = setOf(".git", ".idea", "node_modules", "target", "build", "out", "dist", ".gradle")
    }
}
