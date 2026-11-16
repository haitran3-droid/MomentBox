// IAudioPeer.aidl
package com.ntq.training.momentbox.feature.player;

oneway interface IAudioPeer {
 void onRequestFocus(long nonce);
 void onPlaybackChanged(boolean active);
 void onClosed();
}