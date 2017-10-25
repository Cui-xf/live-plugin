
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import liveplugin.PluginUtil
import liveplugin.show

val javaIsSupportedByIde = Language.findLanguageByID("JAVA") != null
if (javaIsSupportedByIde) {
    PluginUtil.registerIntention(pluginDisposable, JavaFinalFieldIntention())
    if (!isIdeStartup) show("Reloaded 'Finalize Java Fields' intention")
}

/**
 * See also in IJ sources [com.siyeh.ig.fixes.MakeFieldFinalFix].
 */
class JavaFinalFieldIntention: PsiElementBaseIntentionAction() {
    private var isFinal = false

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        if (!element.isInJavaFile()) return false
        val field = element.findParent(PsiField::class.java) ?: return false

        isFinal = field.hasModifierProperty("final")
        text = if (isFinal) "Make 'non-final'" else "Make 'final'"
        return true
    }

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val modifiers = element.findParent(PsiField::class.java)?.modifierList ?: return
        modifiers.setModifierProperty("final", !isFinal)
    }

    override fun getFamilyName() = "Make Java Field (Non-)Final"

    private fun <T> PsiElement?.findParent(aClass: Class<T>): T? = when {
        this == null -> null
        aClass.isAssignableFrom(this.javaClass) -> this as T
        else -> this.parent.findParent(aClass)
    }

    private fun PsiElement.isInJavaFile(): Boolean {
        val fileType = containingFile?.fileType ?: return false
        return fileType is LanguageFileType && fileType.language.id == "JAVA"
    }
}