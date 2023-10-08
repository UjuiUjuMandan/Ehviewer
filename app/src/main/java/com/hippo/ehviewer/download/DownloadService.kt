/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.download

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.IntDef
import androidx.collection.LongSparseArray
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.DownloadsScene
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.LongList
import com.hippo.ehviewer.util.ReadableTime
import com.hippo.ehviewer.util.SimpleHandler
import com.hippo.ehviewer.util.getParcelableExtraCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service(), DownloadManager.DownloadListener, CoroutineScope {
    override val coroutineContext = Dispatchers.IO + SupervisorJob()
    private val deferredMgr = async { DownloadManager.apply { setDownloadListener(this@DownloadService) } }
    private var mNotifyManager: NotificationManagerCompat? = null
    private var mDownloadingBuilder: NotificationCompat.Builder? = null
    private var mDownloadedBuilder: NotificationCompat.Builder? = null
    private var m509dBuilder: NotificationCompat.Builder? = null
    private var mDownloadingDelay: NotificationDelay? = null
    private var mDownloadedDelay: NotificationDelay? = null
    private var m509Delay: NotificationDelay? = null
    private var mChannelID: String? = null

    override fun onCreate() {
        super.onCreate()
        mChannelID = "$packageName.download"
        mNotifyManager = NotificationManagerCompat.from(this)
        mNotifyManager!!.createNotificationChannel(
            NotificationChannelCompat.Builder(mChannelID!!, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.download_service)).build(),
        )
        ensureDownloadingBuilder()
        mDownloadingBuilder!!.setContentTitle(getString(R.string.download_service))
            .setContentText(null)
            .setSubText(null)
            .setProgress(0, 0, true)
        startForeground(ID_DOWNLOADING, mDownloadingBuilder!!.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        val scope = this
        launch {
            deferredMgr.await().setDownloadListener(null)
            scope.cancel()
        }
        mNotifyManager = null
        mDownloadingBuilder = null
        mDownloadedBuilder = null
        m509dBuilder = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        launch { handleIntent(intent) }
        return START_STICKY
    }

    private suspend fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_START -> {
                val gi = intent.getParcelableExtraCompat<BaseGalleryInfo>(KEY_GALLERY_INFO)
                val label = intent.getStringExtra(KEY_LABEL)
                if (gi != null) {
                    deferredMgr.await().startDownload(gi, label)
                }
            }

            ACTION_START_RANGE -> {
                val gidList = intent.getParcelableExtraCompat<LongList>(KEY_GID_LIST)
                if (gidList != null) {
                    deferredMgr.await().startRangeDownload(gidList)
                }
            }

            ACTION_START_ALL -> {
                deferredMgr.await().startAllDownload()
            }

            ACTION_STOP -> {
                val gid = intent.getLongExtra(KEY_GID, -1)
                if (gid != -1L) {
                    deferredMgr.await().stopDownload(gid)
                }
            }

            ACTION_STOP_CURRENT -> deferredMgr.await().stopCurrentDownload()

            ACTION_STOP_RANGE -> {
                val gidList = intent.getParcelableExtraCompat<LongList>(KEY_GID_LIST)
                if (gidList != null) {
                    deferredMgr.await().stopRangeDownload(gidList)
                }
            }

            ACTION_STOP_ALL -> deferredMgr.await().stopAllDownload()

            ACTION_DELETE -> {
                val gid = intent.getLongExtra(KEY_GID, -1)
                if (gid != -1L) {
                    deferredMgr.await().deleteDownload(gid)
                }
            }

            ACTION_DELETE_RANGE -> {
                val gidList = intent.getParcelableExtraCompat<LongList>(KEY_GID_LIST)
                if (gidList != null) {
                    deferredMgr.await().deleteRangeDownload(gidList)
                }
            }

            ACTION_CLEAR -> {
                clear()
            }
        }
        checkStopSelf()
    }

    override fun onBind(intent: Intent) = error("No bindService")

    private fun ensureDownloadingBuilder() {
        if (mDownloadingBuilder != null) {
            return
        }
        val stopAllIntent = Intent(this, DownloadService::class.java)
        stopAllIntent.action = ACTION_STOP_ALL
        val piStopAll =
            PendingIntent.getService(this, 0, stopAllIntent, PendingIntent.FLAG_IMMUTABLE)
        mDownloadingBuilder = NotificationCompat.Builder(applicationContext, mChannelID!!)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                R.drawable.v_pause_x24,
                getString(R.string.stat_download_action_stop_all),
                piStopAll,
            )
            .setShowWhen(false)
            .setChannelId(mChannelID!!)
        mDownloadingDelay =
            NotificationDelay(this, mNotifyManager!!, mDownloadingBuilder!!, ID_DOWNLOADING)
    }

    private fun ensureDownloadedBuilder() {
        if (mDownloadedBuilder != null) {
            return
        }
        val clearIntent = Intent(this, DownloadService::class.java)
        clearIntent.action = ACTION_CLEAR
        val piClear = PendingIntent.getService(this, 0, clearIntent, PendingIntent.FLAG_IMMUTABLE)
        val bundle = Bundle()
        bundle.putString(DownloadsScene.KEY_ACTION, DownloadsScene.ACTION_CLEAR_DOWNLOAD_SERVICE)
        val activityIntent = Intent(this, MainActivity::class.java)
        activityIntent.action = ACTION_START_DOWNLOADSCENE
        activityIntent.putExtra(ACTION_START_DOWNLOADSCENE_ARGS, bundle)
        val piActivity = PendingIntent.getActivity(
            this@DownloadService,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mDownloadedBuilder = NotificationCompat.Builder(applicationContext, mChannelID!!)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.stat_download_done_title))
            .setDeleteIntent(piClear)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(piActivity)
        mDownloadedDelay =
            NotificationDelay(this, mNotifyManager!!, mDownloadedBuilder!!, ID_DOWNLOADED)
    }

    private fun ensure509Builder() {
        if (m509dBuilder != null) {
            return
        }
        m509dBuilder = NotificationCompat.Builder(applicationContext, mChannelID!!)
            .setSmallIcon(R.drawable.ic_baseline_warning_24)
            .setContentText(getString(R.string.stat_509_alert_title))
            .setContentText(getString(R.string.stat_509_alert_text))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(getString(R.string.stat_509_alert_text)),
            )
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
        m509Delay = NotificationDelay(this, mNotifyManager!!, m509dBuilder!!, ID_509)
    }

    override fun onGet509() {
        launch {
            deferredMgr.await().stopAllDownload()
            if (mNotifyManager == null) {
                return@launch
            }
            ensure509Builder()
            m509dBuilder!!.setWhen(System.currentTimeMillis())
            m509Delay!!.show()
        }
    }

    override fun onStart(info: DownloadInfo) {
        if (mNotifyManager == null) {
            return
        }
        ensureDownloadingBuilder()
        val bundle = Bundle()
        bundle.putLong(DownloadsScene.KEY_GID, info.gid)
        val activityIntent = Intent(this, MainActivity::class.java)
        activityIntent.action = ACTION_START_DOWNLOADSCENE
        activityIntent.putExtra(ACTION_START_DOWNLOADSCENE_ARGS, bundle)
        val piActivity = PendingIntent.getActivity(
            this@DownloadService,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mDownloadingBuilder!!.setContentTitle(EhUtils.getSuitableTitle(info))
            .setContentText(null)
            .setSubText(null)
            .setProgress(0, 0, true)
            .setContentIntent(piActivity)
        mDownloadingDelay!!.startForeground()
    }

    private fun onUpdate(info: DownloadInfo) {
        if (mNotifyManager == null) {
            return
        }
        ensureDownloadingBuilder()
        var speed = info.speed
        if (speed < 0) {
            speed = 0
        }
        var text = FileUtils.humanReadableByteCount(speed, false) + "/s"
        val remaining = info.remaining
        text = if (remaining >= 0) {
            getString(
                R.string.download_speed_text_2,
                text,
                ReadableTime.getShortTimeInterval(remaining),
            )
        } else {
            getString(R.string.download_speed_text, text)
        }
        mDownloadingBuilder!!.setContentTitle(EhUtils.getSuitableTitle(info))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(if (info.total == -1 || info.finished == -1) null else info.finished.toString() + "/" + info.total)
            .setProgress(info.total, info.finished, false)
        mDownloadingDelay!!.startForeground()
    }

    override fun onDownload(info: DownloadInfo) {
        onUpdate(info)
    }

    override fun onGetPage(info: DownloadInfo) {
        onUpdate(info)
    }

    override fun onFinish(info: DownloadInfo) {
        if (mNotifyManager == null) {
            return
        }
        if (null != mDownloadingDelay) {
            mDownloadingDelay!!.cancel()
        }
        ensureDownloadedBuilder()
        val finish = info.state == DownloadInfo.STATE_FINISH
        val gid = info.gid
        val index = sItemStateArray.indexOfKey(gid)
        if (index < 0) { // Not contain
            sItemStateArray.put(gid, finish)
            sItemTitleArray.put(gid, EhUtils.getSuitableTitle(info))
            sDownloadedCount++
            if (finish) {
                sFinishedCount++
            } else {
                sFailedCount++
            }
        } else { // Contain
            val oldFinish = sItemStateArray.valueAt(index)
            sItemStateArray.put(gid, finish)
            sItemTitleArray.put(gid, EhUtils.getSuitableTitle(info))
            if (oldFinish && !finish) {
                sFinishedCount--
                sFailedCount++
            } else if (!oldFinish && finish) {
                sFinishedCount++
                sFailedCount--
            }
        }
        val text: String
        val needStyle: Boolean
        if (sFinishedCount != 0 && sFailedCount == 0) {
            if (sFinishedCount == 1) {
                text = if (sItemTitleArray.size() >= 1) {
                    getString(
                        R.string.stat_download_done_line_succeeded,
                        sItemTitleArray.valueAt(0),
                    )
                } else {
                    Log.d("TAG", "WTF, sItemTitleArray is null")
                    getString(R.string.error_unknown)
                }
                needStyle = false
            } else {
                text = getString(R.string.stat_download_done_text_succeeded, sFinishedCount)
                needStyle = true
            }
        } else if (sFinishedCount == 0 && sFailedCount != 0) {
            if (sFailedCount == 1) {
                text = if (sItemTitleArray.size() >= 1) {
                    getString(
                        R.string.stat_download_done_line_failed,
                        sItemTitleArray.valueAt(0),
                    )
                } else {
                    Log.d("TAG", "WTF, sItemTitleArray is null")
                    getString(R.string.error_unknown)
                }
                needStyle = false
            } else {
                text = getString(R.string.stat_download_done_text_failed, sFailedCount)
                needStyle = true
            }
        } else {
            text = getString(R.string.stat_download_done_text_mix, sFinishedCount, sFailedCount)
            needStyle = true
        }
        val style: NotificationCompat.InboxStyle?
        if (needStyle) {
            style = NotificationCompat.InboxStyle()
            style.setBigContentTitle(getString(R.string.stat_download_done_title))
            val stateArray = sItemStateArray
            var i = 0
            val n = stateArray.size()
            while (i < n) {
                val id = stateArray.keyAt(i)
                val fin = stateArray.valueAt(i)
                val title = sItemTitleArray[id]
                if (title == null) {
                    i++
                    continue
                }
                style.addLine(
                    getString(
                        if (fin) R.string.stat_download_done_line_succeeded else R.string.stat_download_done_line_failed,
                        title,
                    ),
                )
                i++
            }
        } else {
            style = null
        }
        mDownloadedBuilder!!.setContentText(text)
            .setStyle(style)
            .setWhen(System.currentTimeMillis())
            .setNumber(sDownloadedCount)
        mDownloadedDelay!!.show()
        checkStopSelf()
    }

    override fun onCancel(info: DownloadInfo) {
        if (mNotifyManager == null) {
            return
        }
        if (null != mDownloadingDelay) {
            mDownloadingDelay!!.cancel()
        }
        checkStopSelf()
    }

    private fun checkStopSelf() {
        launch {
            if (deferredMgr.await().isIdle) {
                ServiceCompat.stopForeground(this@DownloadService, STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private class NotificationDelay(
        private var mService: Service,
        private val mNotifyManager: NotificationManagerCompat,
        private val mBuilder: NotificationCompat.Builder,
        private val mId: Int,
    ) : Runnable {
        private var mLastTime: Long = 0
        private var mPosted = false

        // false for show, true for cancel
        @Ops
        private var mOps = 0

        fun show() {
            if (mPosted) {
                mOps = OPS_NOTIFY
            } else {
                val now = SystemClock.currentThreadTimeMillis()
                if (now - mLastTime > DELAY) {
                    // Wait long enough, do it now
                    if (ActivityCompat.checkSelfPermission(
                            mService,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                    mNotifyManager.notify(mId, mBuilder.build())
                } else {
                    // Too quick, post delay
                    mOps = OPS_NOTIFY
                    mPosted = true
                    SimpleHandler.postDelayed(this, DELAY)
                }
                mLastTime = now
            }
        }

        fun cancel() {
            if (mPosted) {
                mOps = OPS_CANCEL
            } else {
                val now = SystemClock.currentThreadTimeMillis()
                if (now - mLastTime > DELAY) {
                    // Wait long enough, do it now
                    mNotifyManager.cancel(mId)
                } else {
                    // Too quick, post delay
                    mOps = OPS_CANCEL
                    mPosted = true
                    SimpleHandler.postDelayed(this, DELAY)
                }
            }
        }

        fun startForeground() {
            if (mPosted) {
                mOps = OPS_START_FOREGROUND
            } else {
                val now = SystemClock.currentThreadTimeMillis()
                if (now - mLastTime > DELAY) {
                    // Wait long enough, do it now
                    mService.startForeground(mId, mBuilder.build())
                } else {
                    // Too quick, post delay
                    mOps = OPS_START_FOREGROUND
                    mPosted = true
                    SimpleHandler.postDelayed(this, DELAY)
                }
            }
        }

        override fun run() {
            mPosted = false
            when (mOps) {
                OPS_NOTIFY -> {
                    if (ActivityCompat.checkSelfPermission(
                            mService,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                    mNotifyManager.notify(mId, mBuilder.build())
                }

                OPS_CANCEL -> mNotifyManager.cancel(mId)
                OPS_START_FOREGROUND -> mService.startForeground(mId, mBuilder.build())
            }
        }

        @IntDef(OPS_NOTIFY, OPS_CANCEL, OPS_START_FOREGROUND)
        @Retention(AnnotationRetention.SOURCE)
        private annotation class Ops
        companion object {
            private const val OPS_NOTIFY = 0
            private const val OPS_CANCEL = 1
            private const val OPS_START_FOREGROUND = 2
            private const val DELAY: Long = 1000 // 1s
        }
    }

    companion object {
        const val ACTION_START_DOWNLOADSCENE = "start_download_scene"
        const val ACTION_START_DOWNLOADSCENE_ARGS = "start_download_scene_args"

        const val ACTION_START = "start"
        const val ACTION_START_RANGE = "start_range"
        const val ACTION_START_ALL = "start_all"
        const val ACTION_STOP = "stop"
        const val ACTION_STOP_RANGE = "stop_range"
        const val ACTION_STOP_CURRENT = "stop_current"
        const val ACTION_STOP_ALL = "stop_all"
        const val ACTION_DELETE = "delete"
        const val ACTION_DELETE_RANGE = "delete_range"
        const val ACTION_CLEAR = "clear"
        const val KEY_GALLERY_INFO = "gallery_info"
        const val KEY_LABEL = "label"
        const val KEY_GID = "gid"
        const val KEY_GID_LIST = "gid_list"
        private const val ID_DOWNLOADING = 1
        private const val ID_DOWNLOADED = 2
        private const val ID_509 = 3
        private val sItemStateArray = LongSparseArray<Boolean>()
        private val sItemTitleArray = LongSparseArray<String>()
        private var sFailedCount = 0
        private var sFinishedCount = 0
        private var sDownloadedCount = 0

        fun clear() {
            sFailedCount = 0
            sFinishedCount = 0
            sDownloadedCount = 0
            sItemStateArray.clear()
            sItemTitleArray.clear()
        }
    }
}
