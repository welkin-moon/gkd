package li.songe.gkd.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import li.songe.gkd.MainActivity
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.toast

class RuleRecorderTileService : BaseTileService() {
    override val activeFlow = RuleRecorder.isRecordingFlow

    init {
        onTileClicked {
            val service = A11yService.instance
            if (!RuleRecorder.isRecordingFlow.value) {
                if (service == null) {
                    toast("请先开启 GKD 无障碍服务", forced = true)
                    return@onTileClicked
                }
                RuleRecorder.start(service)
                toast("规则录制已开始", forced = true)
            } else {
                val count = RuleRecorder.stop(service)
                if (count == 0) {
                    toast("没有录制到可用点击", forced = true)
                } else {
                    toast("已录制 $count 个点击，请选择要生成的规则", forced = true)
                    openReviewPage()
                }
            }
        }
    }

    private fun openReviewPage() {
        val intent = Intent(this, MainActivity::class.java).apply {
            data = Uri.parse("gkd://page/5")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (AndroidTarget.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
