package com.example.farolestrela

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object CapXmlParser {
    fun parse(inputStream: InputStream): List<CapMessage> {
        val messages = mutableListOf<CapMessage>()
        val parser: XmlPullParser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(inputStream, null)
        }

        var eventType = parser.eventType
        var currentIdentifier = ""
        var currentHeadline = ""
        var currentDescription = ""
        var currentCategory = ""
        var currentSeverity = ""

        var inAlert = false
        var inInfo = false
        var text = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name.equals("alert", ignoreCase = true)) {
                        inAlert = true
                        currentIdentifier = ""
                        currentHeadline = ""
                        currentDescription = ""
                        currentCategory = ""
                        currentSeverity = ""
                    } else if (name.equals("info", ignoreCase = true)) {
                        inInfo = true
                    }
                }
                XmlPullParser.TEXT -> {
                    text = parser.text ?: ""
                }
                XmlPullParser.END_TAG -> {
                    if (name.equals("identifier", ignoreCase = true) && inAlert) {
                        currentIdentifier = text.trim()
                    } else if (name.equals("headline", ignoreCase = true) && inInfo) {
                        currentHeadline = text.trim()
                    } else if (name.equals("description", ignoreCase = true) && inInfo) {
                        currentDescription = text.trim()
                    } else if (name.equals("category", ignoreCase = true) && inInfo) {
                        currentCategory = text.trim()
                    } else if (name.equals("severity", ignoreCase = true) && inInfo) {
                        currentSeverity = text.trim()
                    } else if (name.equals("alert", ignoreCase = true)) {
                        if (currentIdentifier.isNotBlank()) {
                            messages.add(
                                CapMessage(
                                    identifier = currentIdentifier,
                                    headline = currentHeadline.ifBlank { "Health Hazard Warning" },
                                    description = currentDescription.ifBlank { "No description provided." },
                                    category = currentCategory,
                                    severity = currentSeverity
                                )
                            )
                        }
                        inAlert = false
                    } else if (name.equals("info", ignoreCase = true)) {
                        inInfo = false
                    }
                }
            }
            eventType = parser.next()
        }
        return messages
    }
}
