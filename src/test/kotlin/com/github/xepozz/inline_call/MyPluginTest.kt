package com.github.xepozz.inline_call

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil
import java.io.File

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The test fixture sandbox VFS only whitelists a few roots
        // (project base, idea-sandbox, etc.); our testData lives in the
        // module's src/test/testData which is outside those roots when
        // the build runs from /home/user/inline-call-plugin. Allow it
        // explicitly for the duration of the test.
        val abs = File(testDataPath).absolutePath
        VfsRootAccess.allowRootAccess(testRootDisposable, abs)
    }

    fun testXMLFile() {
        val psiFile = myFixture.configureByText(XmlFileType.INSTANCE, "<foo>bar</foo>")
        val xmlFile = assertInstanceOf(psiFile, XmlFile::class.java)

        assertFalse(PsiErrorElementUtil.hasErrors(project, xmlFile.virtualFile))

        assertNotNull(xmlFile.rootTag)

        xmlFile.rootTag?.let {
            assertEquals("foo", it.name)
            assertEquals("bar", it.value.text)
        }
    }

    fun testRename() {
        myFixture.testRename("foo.xml", "foo_after.xml", "a2")
    }

    override fun getTestDataPath() = "src/test/testData/rename"
}
