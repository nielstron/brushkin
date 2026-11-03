package io.github.nielstron.brushkin.ml

/** Object selection can't run here (no Google Play services, a model crash, or a FOSS build). */
class ObjectSelectUnavailable(msg: String) : Exception(msg)
