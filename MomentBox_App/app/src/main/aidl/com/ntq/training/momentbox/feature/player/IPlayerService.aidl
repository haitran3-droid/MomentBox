package com.ntq.training.momentbox.feature.player;
import com.ntq.training.momentbox.feature.player.Track;
import com.ntq.training.momentbox.feature.player.PlayerState;
import com.ntq.training.momentbox.feature.player.IPlayerCallback;
import com.ntq.training.momentbox.feature.player.IAudioPeer;
interface IPlayerService {
 void registerAudioPeer(IAudioPeer peer);
 void unregisterAudioPeer(IAudioPeer peer);
 void confirmFocus(long nonce);
 void interruptForMain(boolean transientLoss);
 void setQueue(in List<Track> tracks, int startIndex);
 void play();
 void pause();
 void seekTo(long positionMs);
 void next();
 void previous();
 void setSpeed(float speed);
 void setSleepTimer(long millis);
 void setShuffle(boolean enabled);
 void toggleShuffle();
 void cycleRepeat();
 void setRepeatMode(int mode);
 void setFavorite(String trackId, boolean favorite);
 void close();
 PlayerState getState();
 void registerCallback(IPlayerCallback callback);
 void unregisterCallback(IPlayerCallback callback);
}
