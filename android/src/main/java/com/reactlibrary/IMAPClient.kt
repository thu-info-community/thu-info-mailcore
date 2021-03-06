package com.reactlibrary

import android.util.Log
import com.facebook.react.bridge.*
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility

class IMAPClient : AbstractMailClient() {
    private lateinit var imapStore: IMAPStore

    override fun init(userCredential: UserCredential, promise: Promise) {
        safeThread(promise) {
            imapStore = (Session.getInstance(Properties().apply {
                put("mail.store.protocol", "imap")
                put("mail.imap.host", userCredential.hostname)
            }).getStore("imap") as IMAPStore).apply {
                connect(userCredential.username, userCredential.password)
            }
            promise.callback()
        }
    }

    private fun makeMailData(message: MimeMessage) = Arguments.createMap().apply {
        putMap("headers", Arguments.createMap())
        putString("id", message.messageID)
        // http://libmailcore.com/api/java/com/libmailcore/MessageFlag.html
        putInt("flags", if (message.flags.contains(Flags.Flag.SEEN)) 1 else 0)
        putString("from", EmailAddress(message.from[0] as InternetAddress).toString())
        putString("subject", message.subject ?: "[无主题]")
        putString("date", message.sentDate.toString())
        putInt("attachments", 0)
    }

    fun createFolder(obj: ReadableMap, promise: Promise) {
        safeThread(promise) {
            imapStore.defaultFolder.getFolder(obj.getString("folder")).create(Folder.HOLDS_MESSAGES)
            promise.callback()
        }
    }

    fun renameFolder(obj: ReadableMap, promise: Promise) {
        safeThread(promise) {
            with(imapStore.defaultFolder) {
                val oldFolder = getFolder(obj.getString("folderOldName"))
                val newFolder = getFolder(obj.getString("folderNewName"))
                oldFolder.renameTo(newFolder)
            }
            promise.callback()
        }
    }

    fun deleteFolder(obj: ReadableMap, promise: Promise) {
        safeThread(promise) {
            imapStore.defaultFolder.getFolder(obj.getString("folder")).delete(true)
            promise.callback()
        }
    }

    fun getFolders(promise: Promise) {
        safeThread(promise) {
            promise.callback {
                val a: WritableArray = WritableNativeArray()
                imapStore.defaultFolder.list("*").forEach { folder ->
                    val mapFolder = Arguments.createMap()
                    mapFolder.putString("path", folder.name)
                    mapFolder.putInt("flags", 0)
                    a.pushMap(mapFolder)
                }
                it.putArray("folders", a)
            }
        }
    }

    fun moveEmail(obj: ReadableMap, promise: Promise) {
        safeThread(promise) {
            with(imapStore.defaultFolder) {
                val from = getFolder(obj.getString("folderFrom"))
                val to = getFolder(obj.getString("folderTo"))
                val id = obj.getString("messageId")
                from.open(Folder.READ_WRITE)
                val msg = from.messages.filter { message -> (message as MimeMessage).messageID == id }.toTypedArray()
                from.copyMessages(msg, to)
                from.setFlags(msg, Flags(Flags.Flag.DELETED), true)
            }
            promise.callback()
        }
    }

    fun permanentDeleteEmail(obj: ReadableMap, promise: Promise) {
        safeThread(promise) {
            with(imapStore.defaultFolder) {
                val from = getFolder(obj.getString("folderFrom"))
                val id = obj.getString("messageId")
                from.open(Folder.READ_WRITE)
                from.messages
                    .first { message -> (message as MimeMessage).messageID == id }
                    .setFlag(Flags.Flag.DELETED, true)
            }
            promise.callback()
        }
    }

    fun getMails(obj: ReadableMap, promise: Promise) {
        safeThread(promise) {
            promise.callback {
                val folder = imapStore.getFolder(obj.getString("folder")) as IMAPFolder
                folder.open(Folder.READ_WRITE)
                val mails = Arguments.createArray()
                for (message in folder.messages) {
                    mails.pushMap(makeMailData(message as MimeMessage))
                }
                it.putArray("mails", mails)
            }
        }
    }

    fun getMailsByRange(obj: ReadableMap, promise: Promise) {
        safeThread(promise) {
            promise.callback {
                val folder = imapStore.getFolder(obj.getString("folder")) as IMAPFolder
                folder.open(Folder.READ_WRITE)
                val from = obj.getInt("from")
                val length = obj.getInt("length")
                val mails = Arguments.createArray()
                for (message in folder.getMessages(from, from + length - 1)) {
                    mails.pushMap(makeMailData(message as MimeMessage))
                }
                it.putArray("mails", mails)
            }
        }
    }

    fun getMail(obj: ReadableMap, promise: Promise) {
        safeThread(promise) {
            val messageId = obj.getString("messageId")
            val folder = imapStore.getFolder(obj.getString("folder")) as IMAPFolder
            var found = false
            folder.open(Folder.READ_WRITE)
            for (message in folder.messages) {
                message as MimeMessage

                fun getAddress(type: Message.RecipientType) =
                    message.getRecipients(type)?.map { EmailAddress(it as InternetAddress) } ?: listOf()

                if (message.messageID == messageId) {
                    found = true
                    promise.callback { mailData ->
                        mailData.putString("id", messageId)
                        mailData.putString("date", message.sentDate.toString())
                        mailData.putInt("flags", if (message.flags.contains(Flags.Flag.SEEN)) 1 else 0)
                        val fromData = Arguments.createMap()
                        val fromAddress = EmailAddress(message.from[0] as InternetAddress)
                        fromData.putString("mailbox", fromAddress.email)
                        fromData.putString("displayName", fromAddress.name)
                        mailData.putMap("from", fromData)
                        val toData = Arguments.createMap()
                        getAddress(Message.RecipientType.TO).forEach {
                            toData.putString(it.email, it.name)
                        }
                        mailData.putMap("to", toData)
                        val ccData = Arguments.createMap()
                        getAddress(Message.RecipientType.CC).forEach {
                            ccData.putString(it.email, it.name)
                        }
                        mailData.putMap("cc", ccData)
                        val bccData = Arguments.createMap()
                        getAddress(Message.RecipientType.BCC).forEach {
                            bccData.putString(it.email, it.name)
                        }
                        mailData.putMap("bcc", bccData)
                        mailData.putString("subject", message.subject ?: "[无主题]")

                        val emailContent = EmailContent()

                        fun getCid(part: Part) =
                            with(part.getHeader("Content-Id")[0]) {
                                if (matches(Regex("<.*>"))) {
                                    substring(indexOf('<') + 1, lastIndexOf('>'))
                                } else {
                                    this
                                }
                            }

                        fun parsePart(part: Part) {
                            when {
                                part.isMimeType("text/plain") ->
                                    emailContent.plain = (part.content as String)
                                part.isMimeType("text/html") ->
                                    emailContent.html = (part.content as String)
                                part.disposition == Part.ATTACHMENT ->
                                    emailContent.attachments.add(part)
                                part.isMimeType("image/*") ->
                                    emailContent.images[getCid(part)] = part
                                part.isMimeType("message/rfc822") ->
                                    parsePart(part.content as Part)
                                part.isMimeType("multipart/*") ->
                                    (part.content as Multipart).run { (0 until count).forEach { parsePart(getBodyPart(it)) } }
                                else ->
                                    Log.e("err", "UNEXPECTED TYPE " + part.contentType)
                            }
                        }

                        parsePart(message)
                        mailData.putString(
                            "body",
                            when {
                                emailContent.hasHtml -> emailContent.html
                                emailContent.hasPlain -> emailContent.plain
                                else -> ""
                            }
                        )

                        val attachmentsData = Arguments.createMap()
                        /* for (attachment in emailContent.attachments) {
                            val attachmentData = Arguments.createMap()
                            attachmentData.putString("filename", attachment.fileName)
                            attachmentData.putString("size", attachment.size.toString())
                            attachmentData.putInt("encoding", attachment.encoding)
                            attachmentData.putString("uniqueId", attachment.uniqueID())
                            attachmentsData.putMap(attachment.partID(), attachmentData)
                        } */
                        mailData.putMap("attachments", attachmentsData)

                        val attachmentsDataInline = Arguments.createMap()
                        /* for (inline in emailContent.images) {
                            val attachment = inline.value
                            val attachmentData = Arguments.createMap()
                            attachmentData.putString("filename", attachment.fileName)
                            attachmentData.putString("size", attachment.size.toString())
                            attachmentData.putInt("encoding", attachment.encoding())
                            attachmentData.putString("cid", attachment.contentID())
                            attachmentData.putString("partID", attachment.partID())
                            attachmentData.putString("uniqueId", attachment.uniqueID())
                            attachmentData.putString("mimepart", attachment.mimeType())
                            attachmentsDataInline.putMap(attachment.partID(), attachmentData)
                        } */
                        mailData.putMap("inline", attachmentsDataInline)

                        mailData.putMap("headers", Arguments.createMap())
                    }
                    break
                }
            }
            if (!found) {
                promise.reject(Error("Message not found."))
            }
        }
    }

    fun statusFolder(obj: ReadableMap, promise: Promise) {
        safeThread(promise) {
            promise.callback { statusData ->
                with(imapStore.getFolder(obj.getString("folder"))) {
                    statusData.putInt("unseenCount", unreadMessageCount)
                    statusData.putInt("messageCount", messageCount)
                    statusData.putInt("recentCount", 0)  // WHAT IS THIS ???
                }
            }
        }
    }

    class EmailAddress(internetAddress: InternetAddress) {
        private fun getName(s: String) = with(s.indexOf('@')) { if (this == -1) s else s.substring(0, this) }

        val email = internetAddress.address ?: "someone@unknown.com"
        val name = internetAddress.personal ?: getName(email)

        override fun toString() = "$name<$email>"
    }

    class EmailContent {
        lateinit var plain: String
        lateinit var html: String
        val attachments = mutableListOf<Part>()
        val images = mutableMapOf<String, Part>()
        val hasPlain: Boolean get() = ::plain.isInitialized
        val hasHtml: Boolean get() = ::html.isInitialized

        private fun Part.decodedFileName(): String = MimeUtility.decodeText(fileName)

        override fun toString(): String {
            val result = StringBuilder()
            if (hasPlain)
                result.append("text/plain:\n$plain\n")
            if (hasHtml)
                result.append("text/html:\n$html\n")
            result.append("attachments: ${attachments.joinToString(", ") { it.decodedFileName() }}\n")
            return result.toString()
        }
    }

}