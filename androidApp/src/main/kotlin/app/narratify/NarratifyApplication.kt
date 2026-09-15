package app.narratify

import android.app.Application

class NarratifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KokoroOnnxRuntime.registerIfApproved(NarratifyNeuralVoiceCatalog.all)
    }
}
