// Copyright (C) 2018 GerritForge Ltd
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package jenkins.plugins.gerrit;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.model.Action;
import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitRemoteHeadRefAction;
import jenkins.plugins.git.GitSCMSourceContext;
import jenkins.plugins.git.GitSCMSourceRequest;
import jenkins.scm.api.*;
import jenkins.scm.api.trait.SCMSourceRequest;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jenkinsci.plugins.gitclient.FetchCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

public abstract class AbstractGerritSCMSource extends AbstractGitSCMSource {
  public static final String R_CHANGES = "refs/changes/";
  public static final String REF_SPEC_CHANGES = "+refs/changes/*:refs/remotes/@{remote}/*";

  public interface Retriever<T> {
    T run(GitClient client, String remoteName) throws IOException, InterruptedException;
  }

  public AbstractGerritSCMSource() {}

  @Deprecated
  public AbstractGerritSCMSource(String id) {
    setId(id);
  }

  /** {@inheritDoc} */
  @Override
  @SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "Known non-serializable this")
  protected void retrieve(
      @CheckForNull SCMSourceCriteria criteria,
      @NonNull SCMHeadObserver observer,
      @CheckForNull SCMHeadEvent<?> event,
      @NonNull final TaskListener listener)
      throws IOException, InterruptedException {
    final GitSCMSourceContext context =
        new GitSCMSourceContext<>(criteria, observer).withTraits(getTraits());
    doRetrieve(
        new Retriever<Void>() {
          @Override
          public Void run(GitClient client, String remoteName)
              throws IOException, InterruptedException {
            final Repository repository = client.getRepository();
            try (RevWalk walk = new RevWalk(repository);
                GitSCMSourceRequest request =
                    context.newRequest(AbstractGerritSCMSource.this, listener)) {
              Map<String, ObjectId> remoteReferences = null;
              if (context.wantBranches() || context.wantTags()) {
                listener.getLogger().println("Listing remote references...");
                remoteReferences =
                    client.getRemoteReferences(
                        client.getRemoteUrl(remoteName), null, false, context.wantTags());
              }
              if (context.wantBranches()) {
                discoverBranches(repository, walk, request, remoteReferences);
              }
              if (context.wantTags()) {
                // TODO
              }
            }
            return null;
          }

          private void discoverBranches(
              final Repository repository,
              final RevWalk walk,
              GitSCMSourceRequest request,
              final Map<String, ObjectId> remoteReferences)
              throws IOException, InterruptedException {
            listener.getLogger().println("Checking branches ...");
            listener.getLogger().println(remoteReferences);
            Map<String, ObjectId> filteredRefs = filterRemoteReferences(remoteReferences);
            listener.getLogger().println("Filtered branches ...");
            listener.getLogger().println(filteredRefs);
            walk.setRetainBody(false);
            int branchesCount = 0;
            int changesCount = 0;

            for (final Map.Entry<String, ObjectId> ref : filteredRefs.entrySet()) {
              String refKey = ref.getKey();
              if (!refKey.startsWith(Constants.R_HEADS) && !refKey.startsWith(R_CHANGES)) {
                listener.getLogger().println("Skipping branches " + refKey);
                continue;
              }

              String refName = ref.getKey();
              if (refName.startsWith(R_CHANGES)) {
                if (processChangeRequest(repository, walk, request, ref, listener)) {
                  listener
                      .getLogger()
                      .format("Processed %d changes (query complete)%n", changesCount);
                  changesCount++;
                  return;
                }
              } else {
                if (processBranchRequest(repository, walk, request, ref, listener)) {
                  listener
                      .getLogger()
                      .format("Processed %d branches (query complete)%n", branchesCount);
                  branchesCount++;
                  return;
                }
              }
            }
            listener.getLogger().format("Processed %d branches%n", branchesCount);
            listener.getLogger().format("Processed %d changes%n", changesCount);
          }
        },
        context,
        listener,
        true);
  }

  /** {@inheritDoc} */
  @NonNull
  @Override
  protected List<Action> retrieveActions(
      @CheckForNull SCMSourceEvent event, @NonNull TaskListener listener)
      throws IOException, InterruptedException {
    return doRetrieve(
        new Retriever<List<Action>>() {
          @Override
          public List<Action> run(GitClient client, String remoteName)
              throws IOException, InterruptedException {
            Map<String, String> symrefs = client.getRemoteSymbolicReferences(getRemote(), null);
            if (symrefs.containsKey(Constants.HEAD)) {
              // Hurrah! The Server is Git 1.8.5 or newer and our client has symref reporting
              String target = symrefs.get(Constants.HEAD);
              if (target.startsWith(Constants.R_HEADS)) {
                // shorten standard names
                target = target.substring(Constants.R_HEADS.length());
              }
              List<Action> result = new ArrayList<>();
              if (StringUtils.isNotBlank(target)) {
                result.add(new GitRemoteHeadRefAction(getRemote(), target));
              }
              result.add(new GerritLogo());
              return result;
            }

            // Give up, there's no way to get the primary branch
            return new ArrayList<>();
          }
        },
        new GitSCMSourceContext<>(null, SCMHeadObserver.none()).withTraits(getTraits()),
        listener,
        false);
  }

  private boolean processBranchRequest(
      final Repository repository,
      final RevWalk walk,
      GitSCMSourceRequest request,
      final Map.Entry<String, ObjectId> ref,
      final TaskListener listener)
      throws IOException, InterruptedException {
    final String branchName =
        StringUtils.removeStart(
            StringUtils.removeStart(ref.getKey(), Constants.R_HEADS), R_CHANGES);
    return (request.process(
        new SCMHead(branchName),
        new SCMSourceRequest.IntermediateLambda<ObjectId>() {
          @Nullable
          @Override
          public ObjectId create() throws IOException, InterruptedException {
            listener.getLogger().println("  Checking branch " + branchName);
            return ref.getValue();
          }
        },
        new SCMSourceRequest.ProbeLambda<SCMHead, ObjectId>() {
          @NonNull
          @Override
          public SCMSourceCriteria.Probe create(
              @NonNull SCMHead head, @Nullable ObjectId revisionInfo)
              throws IOException, InterruptedException {
            RevCommit commit = walk.parseCommit(revisionInfo);
            final long lastModified = TimeUnit.SECONDS.toMillis(commit.getCommitTime());
            final RevTree tree = commit.getTree();
            return new SCMProbe() {
              @Override
              public void close() throws IOException {
                // no-op
              }

              @Override
              public String name() {
                return branchName;
              }

              @Override
              public long lastModified() {
                return lastModified;
              }

              @Override
              @NonNull
              @SuppressFBWarnings(
                value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
                justification =
                    "TreeWalk.forPath can return null, compiler "
                        + "generated code for try with resources handles it"
              )
              public SCMProbeStat stat(@NonNull String path) throws IOException {
                try (TreeWalk tw = TreeWalk.forPath(repository, path, tree)) {
                  if (tw == null) {
                    return SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT);
                  }
                  FileMode fileMode = tw.getFileMode(0);
                  if (fileMode == FileMode.MISSING) {
                    return SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT);
                  }
                  if (fileMode == FileMode.EXECUTABLE_FILE) {
                    return SCMProbeStat.fromType(SCMFile.Type.REGULAR_FILE);
                  }
                  if (fileMode == FileMode.REGULAR_FILE) {
                    return SCMProbeStat.fromType(SCMFile.Type.REGULAR_FILE);
                  }
                  if (fileMode == FileMode.SYMLINK) {
                    return SCMProbeStat.fromType(SCMFile.Type.LINK);
                  }
                  if (fileMode == FileMode.TREE) {
                    return SCMProbeStat.fromType(SCMFile.Type.DIRECTORY);
                  }
                  return SCMProbeStat.fromType(SCMFile.Type.OTHER);
                }
              }
            };
          }
        },
        new SCMSourceRequest.LazyRevisionLambda<SCMHead, SCMRevision, ObjectId>() {
          @NonNull
          @Override
          public SCMRevision create(@NonNull SCMHead head, @Nullable ObjectId intermediate)
              throws IOException, InterruptedException {
            return new SCMRevisionImpl(head, ref.getValue().name());
          }
        },
        new SCMSourceRequest.Witness() {
          @Override
          public void record(@NonNull SCMHead head, SCMRevision revision, boolean isMatch) {
            if (isMatch) {
              listener.getLogger().println("    Met criteria");
            } else {
              listener.getLogger().println("    Does not meet criteria");
            }
          }
        }));
  }

  private boolean processChangeRequest(
      final Repository repository,
      final RevWalk walk,
      GitSCMSourceRequest request,
      final Map.Entry<String, ObjectId> ref,
      final TaskListener listener)
      throws IOException, InterruptedException {
    final String branchName = StringUtils.removeStart(ref.getKey(), R_CHANGES);
    return (request.process(
        new ChangeSCMHead(ref, branchName),
        new SCMSourceRequest.IntermediateLambda<ObjectId>() {
          @Nullable
          @Override
          public ObjectId create() throws IOException, InterruptedException {
            listener.getLogger().println("  Checking change " + branchName);
            return ref.getValue();
          }
        },
        new SCMSourceRequest.ProbeLambda<ChangeSCMHead, ObjectId>() {
          @NonNull
          @Override
          public SCMSourceCriteria.Probe create(
              @NonNull ChangeSCMHead head, @Nullable ObjectId revisionInfo)
              throws IOException, InterruptedException {
            RevCommit commit = walk.parseCommit(revisionInfo);
            final long lastModified = TimeUnit.SECONDS.toMillis(commit.getCommitTime());
            final RevTree tree = commit.getTree();
            return new SCMProbe() {
              @Override
              public void close() throws IOException {
                // no-op
              }

              @Override
              public String name() {
                return branchName;
              }

              @Override
              public long lastModified() {
                return lastModified;
              }

              @Override
              @NonNull
              @SuppressFBWarnings(
                value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
                justification =
                    "TreeWalk.forPath can return null, compiler "
                        + "generated code for try with resources handles it"
              )
              public SCMProbeStat stat(@NonNull String path) throws IOException {
                try (TreeWalk tw = TreeWalk.forPath(repository, path, tree)) {
                  if (tw == null) {
                    return SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT);
                  }
                  FileMode fileMode = tw.getFileMode(0);
                  if (fileMode == FileMode.MISSING) {
                    return SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT);
                  }
                  if (fileMode == FileMode.EXECUTABLE_FILE) {
                    return SCMProbeStat.fromType(SCMFile.Type.REGULAR_FILE);
                  }
                  if (fileMode == FileMode.REGULAR_FILE) {
                    return SCMProbeStat.fromType(SCMFile.Type.REGULAR_FILE);
                  }
                  if (fileMode == FileMode.SYMLINK) {
                    return SCMProbeStat.fromType(SCMFile.Type.LINK);
                  }
                  if (fileMode == FileMode.TREE) {
                    return SCMProbeStat.fromType(SCMFile.Type.DIRECTORY);
                  }
                  return SCMProbeStat.fromType(SCMFile.Type.OTHER);
                }
              }
            };
          }
        },
        new SCMSourceRequest.LazyRevisionLambda<ChangeSCMHead, SCMRevision, ObjectId>() {
          @NonNull
          @Override
          public SCMRevision create(@NonNull ChangeSCMHead head, @Nullable ObjectId intermediate)
              throws IOException, InterruptedException {
            return new SCMRevisionImpl(head, ref.getValue().name());
          }
        },
        new SCMSourceRequest.Witness<ChangeSCMHead, SCMRevision>() {
          @Override
          public void record(@NonNull ChangeSCMHead head, SCMRevision revision, boolean isMatch) {
            if (isMatch) {
              listener.getLogger().println("    Met criteria");
            } else {
              listener.getLogger().println("    Does not meet criteria");
            }
          }
        }));
  }

  private Map<String, ObjectId> filterRemoteReferences(Map<String, ObjectId> gitRefs) {
    Map<Integer, Integer> changes = new HashMap<>();
    Map<String, ObjectId> filteredRefs = new HashMap<>();

    for (Map.Entry<String, ObjectId> gitRef : gitRefs.entrySet()) {
      if (gitRef.getKey().startsWith(R_CHANGES)) {
        String[] changeParts = gitRef.getKey().split("/");
        Integer changeNum = new Integer(changeParts[3]);
        Integer patchSet = new Integer(changeParts[4]);

        Integer latestPatchSet = changes.get(changeNum);
        if (latestPatchSet == null || latestPatchSet < patchSet) {
          changes.put(changeNum, patchSet);
        }
      } else {
        filteredRefs.put(gitRef.getKey(), gitRef.getValue());
      }
    }

    for (Map.Entry<Integer, Integer> change : changes.entrySet()) {
      Integer changeNum = change.getKey();
      Integer patchSet = change.getValue();
      String refName =
          String.format("%s%02d/%d/%d", R_CHANGES, changeNum % 100, changeNum, patchSet);
      ObjectId changeObjectId = gitRefs.get(refName);
      filteredRefs.put(refName, changeObjectId);
    }

    return filteredRefs;
  }

  @NonNull
  protected <T, C extends GitSCMSourceContext<C, R>, R extends GitSCMSourceRequest> T doRetrieve(
      Retriever<T> retriever, @NonNull C context, @NonNull TaskListener listener, boolean prune)
      throws IOException, InterruptedException {
    String cacheEntry = getCacheEntry();
    Lock cacheLock = getCacheLock(cacheEntry);
    cacheLock.lock();
    try {
      File cacheDir = getCacheDir(cacheEntry);
      Git git = Git.with(listener, new EnvVars(EnvVars.masterEnvVars)).in(cacheDir);
      GitTool tool = resolveGitTool(context.gitTool());
      if (tool != null) {
        git.using(tool.getGitExe());
      }
      GitClient client = git.getClient();
      client.addDefaultCredentials(getCredentials());
      if (!client.hasGitRepo()) {
        listener.getLogger().println("Creating git repository in " + cacheDir);
        client.init();
      }
      String remoteName = context.remoteName();
      listener.getLogger().println("Setting " + remoteName + " to " + getRemote());
      client.setRemoteUrl(remoteName, getRemote());
      listener
          .getLogger()
          .println((prune ? "Fetching & pruning " : "Fetching ") + remoteName + "...");
      FetchCommand fetch = client.fetch_();
      if (prune) {
        fetch = fetch.prune();
      }
      URIish remoteURI = null;
      try {
        remoteURI = new URIish(remoteName);
      } catch (URISyntaxException ex) {
        listener.getLogger().println("URI syntax exception for '" + remoteName + "' " + ex);
      }
      fetch.from(remoteURI, context.asRefSpecs()).execute();
      return retriever.run(client, remoteName);
    } finally {
      cacheLock.unlock();
    }
  }
}
