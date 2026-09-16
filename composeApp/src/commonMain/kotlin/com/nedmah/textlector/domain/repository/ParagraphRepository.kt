package com.nedmah.textlector.domain.repository

import com.nedmah.textlector.domain.model.Paragraph
import kotlinx.coroutines.flow.Flow

interface ParagraphRepository {

    fun getParagraphsByDocumentId(documentId: String): Flow<List<Paragraph>>
    suspend fun getParagraphByIndex(documentId: String, index: Int): Paragraph?
    suspend fun getParagraphIndices(documentId: String): List<Int>
    suspend fun getPreparedParagraphIndices(documentId: String): Set<Int>
    suspend fun saveParagraphs(paragraphs: List<Paragraph>): Result<Unit>
    suspend fun updateTtsText(paragraphId: String, ttsText: String): Result<Unit>
    suspend fun deleteParagraphsByDocumentId(documentId: String): Result<Unit>

}