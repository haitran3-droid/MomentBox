package com.ntq.training.momentbox.feature.tabs

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ntq.training.momentbox.core.realtime.SocketPhase
import com.ntq.training.momentbox.data.remote.dto.ChatMemberDto
import com.ntq.training.momentbox.databinding.FragmentChatBinding
import com.ntq.training.momentbox.feature.chat.ChatActivity
import com.ntq.training.momentbox.feature.chat.ChatListViewModel
import com.ntq.training.momentbox.feature.chat.ChatRoomFragment
import com.ntq.training.momentbox.feature.chat.ConversationAdapter
import com.ntq.training.momentbox.feature.chat.ConversationItem
import com.ntq.training.momentbox.feature.chat.NewConversationDialog
import com.ntq.training.momentbox.feature.chat.OnlineAdapter
import com.ntq.training.momentbox.feature.chat.socketLabels
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import kotlinx.coroutines.launch

class ChatFragment:Fragment() {
    private var binding:FragmentChatBinding?=null
    private val app get()=requireActivity().application as MomentBoxApp
    private val model:ChatListViewModel by viewModels {object:AbstractSavedStateViewModelFactory(this,arguments) {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(key:String,c:Class<T>,saved:SavedStateHandle):T=ChatListViewModel(app.chatRuntime,app.database.chatDao(),saved) as T
    }}
    override fun onCreateView(inflater:LayoutInflater,parent:ViewGroup?,saved:Bundle?):View=FragmentChatBinding.inflate(inflater,parent,false).also {binding=it}.root
    override fun onViewCreated(view:View,saved:Bundle?) {
        val b=binding!!.listPanel
        if(arguments?.getBoolean("listOnly")==true) {
            binding!!.chatDetail.isVisible=false
            b.root.layoutParams=b.root.layoutParams.apply {width=ViewGroup.LayoutParams.MATCH_PARENT}
        }
        phoneDetailPending=saved?.getBoolean("hadDetail")==true && !wide
        val back=object:OnBackPressedCallback(false) {override fun handleOnBackPressed() {closeDetail()}}
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,back)
        detailBack=back
        val conversations=ConversationAdapter {open(it)}
        val online=OnlineAdapter {NewConversationDialog.forUser(it).show(childFragmentManager,"following")}
        b.conversations.layoutManager=LinearLayoutManager(requireContext());b.conversations.adapter=conversations
        b.onlineUsers.layoutManager=LinearLayoutManager(requireContext(),LinearLayoutManager.HORIZONTAL,false);b.onlineUsers.adapter=online
        b.search.setText(model.search.value);b.search.doAfterTextChanged {model.search(it?.toString().orEmpty())}
        b.newChat.setOnClickListener {if(childFragmentManager.findFragmentByTag("following")==null) NewConversationDialog().show(childFragmentManager,"following")}
        b.error.setOnClickListener {model.refresh()};b.connection.setOnClickListener {app.socketManager.connect();model.refresh()}
        childFragmentManager.setFragmentResultListener("conversation",viewLifecycleOwner) {_,result->open(result.getLong("id"))}
        viewLifecycleOwner.lifecycleScope.launch {viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {model.selected.collect {id->
                if(wide && arguments?.getBoolean("listOnly")!=true && id>0) showDetail(id)
                detailBack?.isEnabled=wide && id>0 && !isHidden && arguments?.getBoolean("listOnly")!=true
            }}

            launch {model.state.collect {state->
                conversations.submitList(state.rows.map {row->
                    val members=runCatching {Gson().fromJson<List<ChatMemberDto>>(row.conversation.membersJson,object:TypeToken<List<ChatMemberDto>>(){}.type)}.getOrDefault(emptyList())
                    ConversationItem(row,state.users.any {it.online && members.any {m->m.id==it.id}},state.typing[row.conversation.id].orEmpty().isNotEmpty(),state.owner)
                });online.submitList(state.users.filter {it.online});b.onlineUsers.isVisible=state.users.any {it.online};b.empty.isVisible=state.rows.isEmpty()
            }}
            launch {socketLabels(requireContext(),app.socketManager).collect { (state,label)->
                b.connection.text=label
                val connected=state.phase==SocketPhase.CONNECTED
                b.connection.setTextColor(ContextCompat.getColor(requireContext(),if(connected) R.color.mood_1_text else R.color.chip_api_mock_text))
                b.connection.background=GradientDrawable().apply {cornerRadius=12*resources.displayMetrics.density;setColor(if(connected) 0xFFDCFCE7.toInt() else 0xFFFFF4E0.toInt())}
            }}
            launch {app.chatRuntime.error.collect {b.error.isVisible=it!=null}}
        }}
        model.refresh()
    }
    private val wide get()=resources.configuration.smallestScreenWidthDp>=600
    private var detailBack:OnBackPressedCallback?=null
    private var phoneDetailPending=false
    private val chatLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
        if(result.resultCode==Activity.RESULT_OK) {
            val data=result.data;val session=app.chatRuntime.session.value
            if(session!=null && data?.getStringExtra("owner")==session.ownerId && data.getStringExtra("source")==session.source) {
                val id=data.getLongExtra("conversationId",-1);if(id>0) {
                    model.select(id)
                    (childFragmentManager.findFragmentByTag("tablet-room") as? ChatRoomFragment)?.let {it.select(id);it.restoreDraft(data.getStringExtra("draft").orEmpty())}
                }
            }
        }
    }
    private fun open(id:Long) {
        model.select(id)
        if(arguments?.getBoolean("listOnly")==true && requireActivity() is ChatActivity) (requireActivity() as ChatActivity).select(id)
        else if(wide) Unit else chatLauncher.launch(ChatActivity.intent(requireContext(),id))
    }
    private fun showDetail(id:Long) {
        if(childFragmentManager.isStateSaved || binding==null) return
        val previous=childFragmentManager.findFragmentByTag("tablet-room") as? ChatRoomFragment
        if(previous!=null) {previous.select(id);childFragmentManager.beginTransaction().show(previous).setMaxLifecycle(previous,Lifecycle.State.RESUMED).commit()}
        else childFragmentManager.beginTransaction().replace(binding!!.chatDetail.id,ChatRoomFragment.newInstance(id),"tablet-room").commit()
    }
    fun closeDetail() {
        model.clearSelection();detailBack?.isEnabled=false
        (childFragmentManager.findFragmentByTag("tablet-room") as? ChatRoomFragment)?.let {childFragmentManager.beginTransaction().hide(it).setMaxLifecycle(it,Lifecycle.State.STARTED).commit()}
    }
    private fun maybePhoneDetail() {
        if(phoneDetailPending && !isHidden && !wide && binding!=null && model.selected.value>0) {
            phoneDetailPending=false
            val draft=(childFragmentManager.findFragmentByTag("tablet-room") as? ChatRoomFragment)?.currentDraft().orEmpty()
            chatLauncher.launch(ChatActivity.intent(requireContext(),model.selected.value,draft))
        }
    }
    override fun onResume() {super.onResume();maybePhoneDetail()}
    override fun onHiddenChanged(hidden:Boolean) {super.onHiddenChanged(hidden)
        detailBack?.isEnabled=!hidden && wide && model.selected.value>0 && arguments?.getBoolean("listOnly")!=true
        if(!hidden) maybePhoneDetail()
    }
    override fun onSaveInstanceState(out:Bundle) {super.onSaveInstanceState(out);out.putBoolean("hadDetail",wide && model.selected.value>0 && arguments?.getBoolean("listOnly")!=true)}
    override fun onDestroyView() {binding?.listPanel?.conversations?.adapter=null;binding?.listPanel?.onlineUsers?.adapter=null;detailBack=null;binding=null;super.onDestroyView()}
}
