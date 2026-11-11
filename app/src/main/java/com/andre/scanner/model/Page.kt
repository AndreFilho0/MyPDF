package com.andre.scanner.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Ponto em pixels da imagem original (já normalizada: sem rotação EXIF, lado maior <= 3000 px). */
@Serializable
data class Pt(val x: Float, val y: Float)

enum class Mode { NATURAL, SCANNER }

enum class PageStatus {
    /** Copiando a foto e detectando os cantos. */
    LOADING,
    /** Cantos detectados, esperando o usuário confirmar. */
    READY,
    /** Corrigindo perspectiva e iluminação. */
    PROCESSING,
    DONE,
    ERROR,
}

data class Page(
    val id: String = UUID.randomUUID().toString(),
    val originalPath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** 4 cantos em coordenadas da original: sup. esq., sup. dir., inf. dir., inf. esq. */
    val corners: List<Pt> = emptyList(),
    /** O que a detecção automática achou; null quando ela falhou. Usado pelo botão Resetar. */
    val detected: List<Pt>? = null,
    val mode: Mode = Mode.NATURAL,
    /** 0, 90, 180, 270. Aplicada na miniatura e no PDF; a imagem processada fica sem girar. */
    val rotation: Int = 0,
    val processedPath: String? = null,
    val thumbPath: String? = null,
    val status: PageStatus = PageStatus.LOADING,
) {
    val isBusy get() = status == PageStatus.LOADING || status == PageStatus.PROCESSING
}
