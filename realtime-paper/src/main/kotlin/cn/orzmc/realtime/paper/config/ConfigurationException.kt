package cn.orzmc.realtime.paper.config

class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
