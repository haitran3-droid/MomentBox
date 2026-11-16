package com.ntq.training.momentbox.feature.player;
import com.ntq.training.momentbox.feature.player.PlayerState;
oneway interface IPlayerCallback {
 void onStateChanged(in PlayerState state);
 void onPositionChanged(long revision, String trackId, long positionMs);
}
