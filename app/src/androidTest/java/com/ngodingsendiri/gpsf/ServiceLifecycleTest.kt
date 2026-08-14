package com.ngodingsendiri.gpsf

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tes lifecycle service di perangkat/emulator (connectedAndroidTest).
 *
 * Sifatnya agnostik lingkungan: di emulator tanpa izin mock, service harus
 * gagal secara GRACEFUL (tidak crash) — dan setelah STOP, isRunning harus
 * kembali false. Di perangkat dengan izin mock, jalur sukses juga berakhir
 * dengan isRunning == false setelah STOP.
 */
@RunWith(AndroidJUnit4::class)
class ServiceLifecycleTest {

    @Test
    fun startThenStop_doesNotCrashAndClearsRunningState() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        context.startService(
            Intent(context, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_START
                putExtra(MockLocationService.EXTRA_LAT, GpsfConstants.DEFAULT_LAT)
                putExtra(MockLocationService.EXTRA_LNG, GpsfConstants.DEFAULT_LNG)
            }
        )
        // Beri waktu onStartCommand + jalur start (atau kegagalan graceful) selesai.
        Thread.sleep(1500)

        context.startService(
            Intent(context, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_STOP
            }
        )
        Thread.sleep(500)

        // Proses selamat; state mock bersih.
        assertFalse(MockLocationService.isRunning.value)
    }
}
