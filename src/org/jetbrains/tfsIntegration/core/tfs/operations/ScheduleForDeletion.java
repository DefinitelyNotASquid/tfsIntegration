/*
 * Copyright 2000-2008 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.tfsIntegration.core.tfs.operations;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.tfsIntegration.core.TFSBundle;
import org.jetbrains.tfsIntegration.core.TFSVcs;
import org.jetbrains.tfsIntegration.core.tfs.*;
import org.jetbrains.tfsIntegration.exceptions.TfsException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ScheduleForDeletion {

  public static Collection<VcsException> execute(Project project, WorkspaceInfo workspace, List<ItemPath> paths) {
    // choose roots
    // recursively undo pending changes except schedule for deletion => map: modified_name->original_name
    // schedule roots for deletion using their original names (+updateLocalVersion)

    Collection<VcsException> errors = new ArrayList<>();

    try {
      RootsCollection.ItemPathRootsCollection roots = new RootsCollection.ItemPathRootsCollection(paths);

      final Collection<PendingChange> pendingChanges = workspace.getServer().getVCS()
        .queryPendingSetsByLocalPaths(workspace.getName(), workspace.getOwnerName(), roots, RecursionType.Full, project,
                                      TFSBundle.message("loading.changes"));

      Collection<String> revert = new ArrayList<>();
      for (PendingChange pendingChange : pendingChanges) {
        ChangeTypeMask change = new ChangeTypeMask(pendingChange.getChg());
        if (!change.contains(ChangeType_type0.Delete)) {
          // TODO assert for possible change types here
          revert.add(pendingChange.getItem());
        }
      }

      final UndoPendingChanges.UndoPendingChangesResult undoResult =
        UndoPendingChanges.execute(project, workspace, revert, true, ApplyProgress.EMPTY, false);
      errors.addAll(undoResult.errors);

      List<ItemPath> undoneRoots = new ArrayList<>(roots.size());
      for (ItemPath originalRoot : roots) {
        ItemPath undoneRoot = undoResult.undonePaths.get(originalRoot);
        undoneRoots.add(undoneRoot != null ? undoneRoot : originalRoot);
      }

      final List<FilePath> scheduleForDeletion = new ArrayList<>();
      StatusProvider.visitByStatus(workspace, undoneRoots, false, null, new StatusVisitor() {

        @Override
        public void unversioned(final @NotNull FilePath localPath, final boolean localItemExists, final @NotNull ServerStatus serverStatus)
          throws TfsException {
          // ignore
        }

        @Override
        public void deleted(final @NotNull FilePath localPath, final boolean localItemExists, final @NotNull ServerStatus serverStatus) {
          // ignore
        }

        @Override
        public void checkedOutForEdit(final @NotNull FilePath localPath,
                                      final boolean localItemExists,
                                      final @NotNull ServerStatus serverStatus) throws TfsException {
          TFSVcs.error("Unexpected status " + serverStatus.getClass().getName() + " for " + localPath.getPresentableUrl());
        }

        @Override
        public void scheduledForAddition(final @NotNull FilePath localPath,
                                         final boolean localItemExists,
                                         final @NotNull ServerStatus serverStatus) {
          TFSVcs.error("Unexpected status " + serverStatus.getClass().getName() + " for " + localPath.getPresentableUrl());
        }

        @Override
        public void scheduledForDeletion(final @NotNull FilePath localPath,
                                         final boolean localItemExists,
                                         final @NotNull ServerStatus serverStatus) {
          TFSVcs.error("Unexpected status " + serverStatus.getClass().getName() + " for " + localPath.getPresentableUrl());
        }

        @Override
        public void outOfDate(final @NotNull FilePath localPath, final boolean localItemExists, final @NotNull ServerStatus serverStatus)
          throws TfsException {
          scheduleForDeletion.add(localPath);
        }

        @Override
        public void upToDate(final @NotNull FilePath localPath, final boolean localItemExists, final @NotNull ServerStatus serverStatus)
          throws TfsException {
          scheduleForDeletion.add(localPath);
        }

        @Override
        public void renamed(final @NotNull FilePath localPath, final boolean localItemExists, final @NotNull ServerStatus serverStatus)
          throws TfsException {
          TFSVcs.error("Unexpected status " + serverStatus.getClass().getName() + " for " + localPath.getPresentableUrl());
        }

        @Override
        public void renamedCheckedOut(final @NotNull FilePath localPath,
                                      final boolean localItemExists,
                                      final @NotNull ServerStatus serverStatus) throws TfsException {
          TFSVcs.error("Unexpected status " + serverStatus.getClass().getName() + " for " + localPath.getPresentableUrl());
        }

        @Override
        public void undeleted(final @NotNull FilePath localPath, final boolean localItemExists, final @NotNull ServerStatus serverStatus)
          throws TfsException {
          TFSVcs.error("Unexpected status " + serverStatus.getClass().getName() + " for " + localPath.getPresentableUrl());
        }
      }, project);

      ResultWithFailures<GetOperation> schedulingForDeletionResults = workspace.getServer().getVCS()
        .scheduleForDeletionAndUpateLocalVersion(workspace.getName(), workspace.getOwnerName(), scheduleForDeletion, project,
                                                 TFSBundle.message("scheduling.for.deletion"));
      errors.addAll(TfsUtil.getVcsExceptions(schedulingForDeletionResults.getFailures()));

      for (GetOperation getOperation : schedulingForDeletionResults.getResult()) {
        TfsFileUtil
          .markFileDirty(project, VersionControlPath.getFilePath(getOperation.getSlocal(), getOperation.getType() == ItemType.Folder));
      }
    }
    catch (TfsException e) {
      errors.add(new VcsException(e));
    }

    return errors;
  }

}
