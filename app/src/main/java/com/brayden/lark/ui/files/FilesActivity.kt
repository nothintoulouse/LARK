package com.brayden.lark.ui.files

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.brayden.lark.data.local.AudioFileEntity
import com.brayden.lark.data.model.FileState
import com.brayden.lark.databinding.ActivityFilesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class FilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilesBinding
    private val viewModel: FilesViewModel by viewModels()
    private lateinit var fileAdapter: FileListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvStorageInfo.text = viewModel.getStorageInfo()

        setupFileList()
        observeViewModel()
    }

    private fun setupFileList() {
        fileAdapter = FileListAdapter(
            onShareClick = { file -> shareFile(file) },
            onDeleteClick = { file -> confirmDelete(file) },
            onConvertClick = { file -> viewModel.convertToWav(file) },
            onPlayClick = { file -> viewModel.playFile(file) },
            onTogglePlayPause = { fileId -> viewModel.togglePlayPause(fileId) },
            onSeek = { positionMs -> viewModel.seekTo(positionMs) }
        )

        binding.recyclerFiles.apply {
            layoutManager = LinearLayoutManager(this@FilesActivity)
            adapter = fileAdapter
        }
    }

    private fun observeViewModel() {
        viewModel.files.observe(this) { files ->
            fileAdapter.submitList(files)
            binding.tvEmpty.isVisible = files.isEmpty()
            binding.recyclerFiles.isVisible = files.isNotEmpty()
        }

        viewModel.playbackState.observe(this) { state ->
            fileAdapter.updatePlaybackState(state)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopPlayback()
    }

    private fun shareFile(audioFile: AudioFileEntity) {
        // Prefer WAV if available, otherwise share Opus
        val state = try { FileState.valueOf(audioFile.state) } catch (e: Exception) { null }
        val path = if (state == FileState.COMPLETE && audioFile.wavPath != null) {
            audioFile.wavPath
        } else {
            audioFile.opusPath
        }

        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (path.endsWith(".wav")) "audio/wav" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share recording"))
    }

    private fun confirmDelete(file: AudioFileEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Recording")
            .setMessage("Delete ${file.filename}? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteFile(file)
            }
            .show()
    }
}
