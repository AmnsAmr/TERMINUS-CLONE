package com.necroware.terminusplayer.data.repository

import java.io.IOException

class DuplicateUploadException(message: String) : IOException(message)

class UploadQuotaExceededException(message: String, cause: Throwable? = null) : IOException(message, cause)

class PermanentTransferException(message: String, cause: Throwable? = null) : IOException(message, cause)
