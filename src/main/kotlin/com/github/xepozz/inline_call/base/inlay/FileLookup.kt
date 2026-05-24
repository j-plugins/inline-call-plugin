package com.github.xepozz.inline_call.base.inlay

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Tiny lookup helper used by inlay action handlers to resolve the
 * editor's underlying [VirtualFile] without forcing each handler to
 * reach into `FileDocumentManager` directly.
 */
internal object FileLookup {
    fun virtualFileFor(editor: Editor): VirtualFile? =
        FileDocumentManager.getInstance().getFile(editor.document)
}
