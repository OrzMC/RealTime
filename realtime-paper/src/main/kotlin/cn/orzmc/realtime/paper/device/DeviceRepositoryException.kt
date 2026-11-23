package cn.orzmc.realtime.paper.device

class DeviceRepositoryException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
