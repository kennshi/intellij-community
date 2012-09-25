package com.intellij.execution;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileAdapter;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.util.Alarm;
import com.intellij.util.Consumer;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class AutoExecutor implements Runnable {
  private final Project project;
  private final Alarm alarm;
  private final Consumer<VirtualFile[]> consumer;
  private final int delay;

  private final MyDocumentAdapter listener;
  private boolean listenerAttached;

  private final Set<VirtualFile> changedFiles = new THashSet<VirtualFile>();
  private boolean wasRequested;

  private final Condition<VirtualFile> documentChangedFilter;

  public AutoExecutor(Project project, Alarm alarm, int delay, Consumer<VirtualFile[]> consumer, Condition<VirtualFile> documentChangedFilter) {
    this.project = project;
    this.alarm = alarm;
    this.delay = delay;
    this.consumer = consumer;
    this.documentChangedFilter = documentChangedFilter;

    listener = new MyDocumentAdapter();

    project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void before(@NotNull List<? extends VFileEvent> events) {
      }

      @Override
      public void after(@NotNull List<? extends VFileEvent> events) {
        for (VFileEvent event : events) {
          if (event instanceof VFileDeleteEvent) {
            synchronized (changedFiles) {
              changedFiles.remove(event.getFile());
            }
          }
        }
      }
    });
  }

  private void addVfsListener() {
    VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileAdapter() {
      @Override
      public void fileDeleted(VirtualFileEvent event) {
        synchronized (changedFiles) {
          changedFiles.remove(event.getFile());
        }
      }
    });
  }

  @Override
  public void run() {
    final VirtualFile[] files;
    synchronized (changedFiles) {
      wasRequested = false;
      files = changedFiles.toArray(new VirtualFile[changedFiles.size()]);
      changedFiles.clear();
    }

    final WolfTheProblemSolver problemSolver = WolfTheProblemSolver.getInstance(project);
    for (VirtualFile file : files) {
      if (problemSolver.hasSyntaxErrors(file)) {
        // threat any other file in queue as dependency of this file — don't flush if some of the queued file is invalid
        // Vladimir.Krivosheev AutoTestManager behavior behavior is preserved.
        // LiveEdit version used another strategy (flush all valid files), but now we use AutoTestManager-inspired strategy
        synchronized (changedFiles) {
          Collections.addAll(changedFiles, files);
        }

        return;
      }
    }

    consumer.consume(files);
  }

  public Project getProject() {
    return project;
  }

  public void activate() {
    if (!listenerAttached) {
      listenerAttached = true;
      EditorFactory.getInstance().getEventMulticaster().addDocumentListener(listener, project);
    }
  }

  public void deactivate() {
    if (listenerAttached) {
      listenerAttached = false;
      EditorFactory.getInstance().getEventMulticaster().removeDocumentListener(listener);
    }
  }

  private class MyDocumentAdapter extends DocumentAdapter {
    public void documentChanged(DocumentEvent event) {
      final Document document = event.getDocument();
      final VirtualFile file = FileDocumentManager.getInstance().getFile(document);
      if (file == null || !documentChangedFilter.value(file)) {
        return;
      }

      synchronized (changedFiles) {
        // changedFiles contains is not enough, because it can contain not-flushed files from prev request (which are not flushed because some is invalid)
        if (!changedFiles.add(file) && wasRequested) {
          return;
        }
      }

      alarm.cancelRequest(AutoExecutor.this);
      alarm.addRequest(AutoExecutor.this, delay);
    }
  }
}