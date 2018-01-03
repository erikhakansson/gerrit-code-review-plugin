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

import static hudson.model.Computer.threadPoolForRemoting;

import com.google.gson.Gson;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.RootAction;
import hudson.model.UnprotectedRootAction;
import hudson.util.SequentialExecutionQueue;
import java.io.*;
import java.util.List;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.Stapler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives github hook.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class GerritWebHook implements UnprotectedRootAction {
  private static final Logger log = LoggerFactory.getLogger(GerritWebHook.class);
  private static final Gson gson = new Gson();

  public static final String URLNAME = "gerrit-webhook";

  private final transient SequentialExecutionQueue queue =
      new SequentialExecutionQueue(threadPoolForRemoting);

  @Override
  public String getIconFileName() {
    return null;
  }

  @Override
  public String getDisplayName() {
    return null;
  }

  @Override
  public String getUrlName() {
    return URLNAME;
  }

  /**
   * If any wants to auto-register hook, then should call this method Example code: {@code
   * GitHubWebHook.get().registerHookFor(job);}
   *
   * @param job not null project to register hook for
   * @deprecated use {@link #registerHookFor(Item)}
   */
  @Deprecated
  public void registerHookFor(Job job) {
    //        reRegisterHookForJob().apply(job);
  }

  /**
   * If any wants to auto-register hook, then should call this method Example code: {@code
   * GitHubWebHook.get().registerHookFor(item);}
   *
   * @param item not null item to register hook for
   * @since 1.25.0
   */
  public void registerHookFor(Item item) {
    //        reRegisterHookForJob().apply(item);
  }

  /** Receives the webhook call */
  @SuppressWarnings("unused")
  public void doIndex() throws IOException {
    HttpServletRequest req = Stapler.getCurrentRequest();
    GerritProjectEvent projectEvent = getBody(req);
    log.info("GerritWebHook invoked for " + projectEvent);
    List<Item> jenkinsItems = Jenkins.getActiveInstance().getAllItems();
    for (Item item : jenkinsItems) {
      if (item instanceof SCMSourceOwner) {
        SCMSourceOwner scmJob = (SCMSourceOwner) item;
        log.info("Found SCM job " + scmJob);
        List<SCMSource> scmSources = scmJob.getSCMSources();
        for (SCMSource scmSource : scmSources) {
          if (scmSource instanceof GerritSCMSource) {
            GerritSCMSource gerritSCMSource = (GerritSCMSource) scmSource;
            if (projectEvent.matches(gerritSCMSource.getRemote())) {
              log.info(
                  "Triggering SCM event for source " + scmSources.get(0) + " on job " + scmJob);
              scmJob.onSCMSourceUpdated(scmSource);
            }
          }
        }
      }
    }
  }

  private GerritProjectEvent getBody(HttpServletRequest req) throws IOException {
    char[] body = new char[req.getContentLength()];
    try (InputStreamReader is = new InputStreamReader(req.getInputStream())) {
      IOUtils.readFully(is, body);
      String bodyString = new String(body);
      log.info("Received body: " + bodyString);
      return gson.fromJson(bodyString, GerritProjectEvent.class);
    }
  }

  public static GerritWebHook get() {
    return Jenkins.getInstance().getExtensionList(RootAction.class).get(GerritWebHook.class);
  }

  @Nonnull
  public static Jenkins getJenkinsInstance() throws IllegalStateException {
    return Jenkins.getInstance();
  }
}
