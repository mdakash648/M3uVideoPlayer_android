import os

res_dir = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\res"

ic_replay_10 = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M11.99,5V1l-5,5l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6s-6,-2.69 -6,-6h-2c0,4.42 3.58,8 8,8s8,-3.58 8,-8S16.41,5 11.99,5zM10.89,16H9.4v-4.26L7.96,12.3l0.36,-1.23l2.42,-0.83h0.15V16zM15.42,12.56c0.41,0.5 0.62,1.2 0.62,2.09c0,0.89 -0.21,1.59 -0.62,2.09c-0.41,0.5 -0.99,0.75 -1.74,0.75c-0.75,0 -1.33,-0.25 -1.74,-0.75c-0.41,-0.5 -0.62,-1.2 -0.62,-2.09c0,-0.89 0.21,-1.59 0.62,-2.09c0.41,-0.5 0.99,-0.75 1.74,-0.75C14.43,11.81 15.01,12.06 15.42,12.56zM14.65,15.77c0.16,-0.27 0.23,-0.7 0.23,-1.31c0,-0.61 -0.08,-1.05 -0.23,-1.31c-0.16,-0.27 -0.44,-0.4 -0.84,-0.4c-0.4,0 -0.68,0.13 -0.84,0.4c-0.16,0.27 -0.23,0.7 -0.23,1.31c0,0.61 0.08,1.05 0.23,1.31c0.16,0.27 0.44,0.4 0.84,0.4C14.21,16.17 14.49,16.03 14.65,15.77z"/>
</vector>"""

ic_forward_10 = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M18,13c0,3.31 -2.69,6 -6,6s-6,-2.69 -6,-6s2.69,-6 6,-6v4l5,-5l-5,-5v4c-4.42,0 -8,3.58 -8,8c0,4.42 3.58,8 8,8s8,-3.58 8,-8H18zM10.89,16H9.4v-4.26L7.96,12.3l0.36,-1.23l2.42,-0.83h0.15V16zM15.42,12.56c0.41,0.5 0.62,1.2 0.62,2.09c0,0.89 -0.21,1.59 -0.62,2.09c-0.41,0.5 -0.99,0.75 -1.74,0.75c-0.75,0 -1.33,-0.25 -1.74,-0.75c-0.41,-0.5 -0.62,-1.2 -0.62,-2.09c0,-0.89 0.21,-1.59 0.62,-2.09c0.41,-0.5 0.99,-0.75 1.74,-0.75C14.43,11.81 15.01,12.06 15.42,12.56zM14.65,15.77c0.16,-0.27 0.23,-0.7 0.23,-1.31c0,-0.61 -0.08,-1.05 -0.23,-1.31c-0.16,-0.27 -0.44,-0.4 -0.84,-0.4c-0.4,0 -0.68,0.13 -0.84,0.4c-0.16,0.27 -0.23,0.7 -0.23,1.31c0,0.61 0.08,1.05 0.23,1.31c0.16,0.27 0.44,0.4 0.84,0.4C14.21,16.17 14.49,16.03 14.65,15.77z"/>
</vector>"""

with open(os.path.join(res_dir, "drawable", "ic_replay_10.xml"), "w", encoding="utf-8") as f:
    f.write(ic_replay_10)

with open(os.path.join(res_dir, "drawable", "ic_forward_10.xml"), "w", encoding="utf-8") as f:
    f.write(ic_forward_10)

layout_file = os.path.join(res_dir, "layout", "activity_player.xml")
with open(layout_file, "r", encoding="utf-8") as f:
    content = f.read()

btnPlayPause_old = """            <!-- promt1  1 - the ABSOLUTE DEFAULT focus target. The remote lands here every time the
                 panel appears and on every brand-new video (see PlayerActivity.focusPlayPause). -->
            <ImageButton
                android:id="@+id/btnPlayPause"
                android:layout_width="64dp"
                android:layout_height="64dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/player_play_pause"
                android:focusable="true"
                android:clickable="true"
                android:foreground="@drawable/bg_player_control_focus"
                android:scaleType="fitCenter"
                android:src="@drawable/ic_pause" />"""

btnPlayPause_new = """            <ImageButton
                android:id="@+id/btnRewind10"
                android:layout_width="52dp"
                android:layout_height="52dp"
                android:layout_marginEnd="12dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Rewind 10s"
                android:focusable="true"
                android:clickable="true"
                android:foreground="@drawable/bg_player_control_focus"
                android:padding="10dp"
                android:scaleType="fitCenter"
                android:src="@drawable/ic_replay_10"
                android:tint="@color/white" />

            <!-- promt1  1 - the ABSOLUTE DEFAULT focus target. The remote lands here every time the
                 panel appears and on every brand-new video (see PlayerActivity.focusPlayPause). -->
            <ImageButton
                android:id="@+id/btnPlayPause"
                android:layout_width="64dp"
                android:layout_height="64dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/player_play_pause"
                android:focusable="true"
                android:clickable="true"
                android:foreground="@drawable/bg_player_control_focus"
                android:scaleType="fitCenter"
                android:src="@drawable/ic_pause" />

            <ImageButton
                android:id="@+id/btnForward10"
                android:layout_width="52dp"
                android:layout_height="52dp"
                android:layout_marginStart="12dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Forward 10s"
                android:focusable="true"
                android:clickable="true"
                android:foreground="@drawable/bg_player_control_focus"
                android:padding="10dp"
                android:scaleType="fitCenter"
                android:src="@drawable/ic_forward_10"
                android:tint="@color/white" />"""

content = content.replace(btnPlayPause_old, btnPlayPause_new)
with open(layout_file, "w", encoding="utf-8") as f:
    f.write(content)

player_file = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\PlayerActivity.kt"
with open(player_file, "r", encoding="utf-8") as f:
    player_content = f.read()

listeners_old = """        btnNext.setOnClickListener { playNext() }
        btnPrevious.setOnClickListener { playPrevious() }"""

listeners_new = """        btnNext.setOnClickListener { playNext() }
        btnPrevious.setOnClickListener { playPrevious() }
        binding.btnRewind10.setOnClickListener {
            engine?.let { player ->
                val pos = (player.positionMs - 10000).coerceAtLeast(0)
                player.seekTo(pos)
                handler.removeCallbacks(hideControlsRunnable)
                scheduleHideControls()
            }
        }
        binding.btnForward10.setOnClickListener {
            engine?.let { player ->
                val duration = player.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                val pos = (player.positionMs + 10000).coerceAtMost(duration)
                player.seekTo(pos)
                handler.removeCallbacks(hideControlsRunnable)
                scheduleHideControls()
            }
        }"""

player_content = player_content.replace(listeners_old, listeners_new)
with open(player_file, "w", encoding="utf-8") as f:
    f.write(player_content)

print("Done add buttons")
