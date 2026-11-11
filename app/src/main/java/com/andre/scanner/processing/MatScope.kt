package com.andre.scanner.processing

import org.opencv.core.Mat

/**
 * A memória de um Mat fica fora do coletor de lixo do Kotlin. Tudo que passa por [track]
 * é liberado no fim do bloco [withMats], mesmo se der exceção ou cancelamento.
 */
class MatScope {
    private val mats = mutableListOf<Mat>()
    fun <T : Mat> T.track(): T = also { mats += it }
    fun mat(): Mat = Mat().track()
    /** Tira o Mat da lista para ele sobreviver ao bloco (quem recebe faz o release). */
    fun <T : Mat> keep(m: T): T = m.also { mats.remove(it) }
    @PublishedApi internal fun releaseAll() { mats.forEach { it.release() }; mats.clear() }
}

inline fun <R> withMats(block: MatScope.() -> R): R {
    val scope = MatScope()
    try {
        return scope.block()
    } finally {
        scope.releaseAll()
    }
}
