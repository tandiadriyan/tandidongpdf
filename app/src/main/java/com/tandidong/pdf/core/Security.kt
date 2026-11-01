package com.tandidong.pdf.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy

data class Permissions(
    val print: Boolean = true,
    val printHighQuality: Boolean = true,
    val modify: Boolean = true,
    val copy: Boolean = true,
    val annotate: Boolean = true,
    val fillForms: Boolean = true,
    val assemble: Boolean = true,
    val accessibility: Boolean = true,
)

data class SecurityInfo(
    val encrypted: Boolean,
    val keyLength: Int,
    val isOwner: Boolean,
    val permissions: Permissions,
)

object Security {
    fun info(doc: PDDocument): SecurityInfo {
        if (!doc.isEncrypted) return SecurityInfo(false, 0, true, Permissions())
        val ap = doc.currentAccessPermission
        val len = try { doc.encryption?.length ?: 0 } catch (e: Exception) { 0 }
        return SecurityInfo(true, len, ap.isOwnerPermission, toPermissions(ap))
    }

    fun toPermissions(ap: AccessPermission) = Permissions(
        print = ap.canPrint(),
        printHighQuality = ap.canPrintFaithful(),
        modify = ap.canModify(),
        copy = ap.canExtractContent(),
        annotate = ap.canModifyAnnotations(),
        fillForms = ap.canFillInForm(),
        assemble = ap.canAssembleDocument(),
        accessibility = ap.canExtractForAccessibility(),
    )

    /**
     * Encrypts the document with AES-256. [userPassword] is required to open the file (may be empty
     * to allow opening without a password); [ownerPassword] unlocks editing restrictions.
     */
    fun protect(doc: PDDocument, userPassword: String, ownerPassword: String, permissions: Permissions = Permissions()) {
        val ap = AccessPermission()
        ap.setCanPrint(permissions.print)
        ap.setCanPrintFaithful(permissions.printHighQuality)
        ap.setCanModify(permissions.modify)
        ap.setCanExtractContent(permissions.copy)
        ap.setCanModifyAnnotations(permissions.annotate)
        ap.setCanFillInForm(permissions.fillForms)
        ap.setCanAssembleDocument(permissions.assemble)
        ap.setCanExtractForAccessibility(permissions.accessibility)
        val owner = ownerPassword.ifEmpty { userPassword.ifEmpty { java.util.UUID.randomUUID().toString() } }
        val policy = StandardProtectionPolicy(owner, userPassword, ap)
        policy.encryptionKeyLength = 256
        doc.isAllSecurityToBeRemoved = false
        doc.protect(policy)
    }

    fun removeSecurity(doc: PDDocument) {
        doc.isAllSecurityToBeRemoved = true
    }
}
