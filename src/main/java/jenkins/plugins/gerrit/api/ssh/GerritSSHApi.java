package jenkins.plugins.gerrit.api.ssh;

import com.google.common.base.Suppliers;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.DraftInput;
import com.google.gerrit.extensions.api.config.Config;
import com.google.gerrit.extensions.api.groups.Groups;
import com.google.gerrit.extensions.api.projects.Projects;
import com.google.gerrit.extensions.restapi.NotImplementedException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.urswolfer.gerrit.client.rest.GerritAuthData;
import com.urswolfer.gerrit.client.rest.accounts.Accounts;
import com.urswolfer.gerrit.client.rest.http.GerritRestClient;
import com.urswolfer.gerrit.client.rest.http.HttpClientBuilderExtension;
import com.urswolfer.gerrit.client.rest.http.accounts.AccountsParser;
import com.urswolfer.gerrit.client.rest.http.accounts.AccountsRestClient;
import com.urswolfer.gerrit.client.rest.http.changes.AddReviewerResultParser;
import com.urswolfer.gerrit.client.rest.http.changes.ChangesParser;
import com.urswolfer.gerrit.client.rest.http.changes.ChangesRestClient;
import com.urswolfer.gerrit.client.rest.http.changes.CommentsParser;
import com.urswolfer.gerrit.client.rest.http.changes.DiffInfoParser;
import com.urswolfer.gerrit.client.rest.http.changes.EditInfoParser;
import com.urswolfer.gerrit.client.rest.http.changes.FileInfoParser;
import com.urswolfer.gerrit.client.rest.http.changes.IncludedInInfoParser;
import com.urswolfer.gerrit.client.rest.http.changes.ReviewResultParser;
import com.urswolfer.gerrit.client.rest.http.changes.ReviewerInfoParser;
import com.urswolfer.gerrit.client.rest.http.changes.SuggestedReviewerInfoParser;
import com.urswolfer.gerrit.client.rest.http.config.ConfigRestClient;
import com.urswolfer.gerrit.client.rest.http.groups.GroupsParser;
import com.urswolfer.gerrit.client.rest.http.groups.GroupsRestClient;
import com.urswolfer.gerrit.client.rest.http.projects.BranchInfoParser;
import com.urswolfer.gerrit.client.rest.http.projects.ProjectsParser;
import com.urswolfer.gerrit.client.rest.http.projects.ProjectsRestClient;
import com.urswolfer.gerrit.client.rest.http.projects.TagInfoParser;
import com.urswolfer.gerrit.client.rest.http.tools.ToolsRestClient;
import com.urswolfer.gerrit.client.rest.tools.Tools;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.eclipse.jgit.transport.URIish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * gerrit-code-review
 *
 * <p>Created by Erik Håkansson on 2018-11-03. Copyright 2018
 */
public class GerritSSHApi extends GerritApi.NotImplemented {
  private final GerritRestClient gerritRestClient;
  private final Supplier<GroupsRestClient> groupsRestClient =
      Suppliers.memoize(
              () ->
                  new GroupsRestClient(
                      GerritSSHApi.this.gerritRestClient,
                      new GroupsParser(GerritSSHApi.this.gerritRestClient.getGson())))
          ::get;
  private final Supplier<AccountsRestClient> accountsRestClient =
      Suppliers.memoize(
              () ->
                  new AccountsRestClient(
                      GerritSSHApi.this.gerritRestClient,
                      new AccountsParser(GerritSSHApi.this.gerritRestClient.getGson())))
          ::get;
  private final Supplier<ChangesRestClient> changesRestClient =
      Suppliers.memoize(
              () ->
                  new ChangesRestClient(
                      GerritSSHApi.this.gerritRestClient,
                      new ChangesParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new CommentsParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new IncludedInInfoParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new FileInfoParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new DiffInfoParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new SuggestedReviewerInfoParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new ReviewerInfoParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new EditInfoParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new AddReviewerResultParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new ReviewResultParser(GerritSSHApi.this.gerritRestClient.getGson())))
          ::get;
  private final Supplier<ConfigRestClient> configRestClient =
      Suppliers.memoize(() -> new ConfigRestClient(GerritSSHApi.this.gerritRestClient))::get;
  private final Supplier<ProjectsRestClient> projectsRestClient =
      Suppliers.memoize(
              () ->
                  new ProjectsRestClient(
                      GerritSSHApi.this.gerritRestClient,
                      new ProjectsParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new BranchInfoParser(GerritSSHApi.this.gerritRestClient.getGson()),
                      new TagInfoParser(GerritSSHApi.this.gerritRestClient.getGson())))
          ::get;
  private final Supplier<ToolsRestClient> toolsRestClient =
      Suppliers.memoize(() -> new ToolsRestClient(GerritSSHApi.this.gerritRestClient))::get;

  public GerritSSHApi(
      SshClient sshClient, URIish gerritApiUrl, GerritAuthData authData, long timeout) {
    this.gerritRestClient = new GerritSSHClient(sshClient, gerritApiUrl, authData, timeout);
  }

  public Accounts accounts() {
    return this.accountsRestClient.get();
  }

  public Changes changes() {
    return this.changesRestClient.get();
  }

  public Config config() {
    return this.configRestClient.get();
  }

  public Groups groups() {
    return this.groupsRestClient.get();
  }

  public Projects projects() {
    return this.projectsRestClient.get();
  }

  public Tools tools() {
    return this.toolsRestClient.get();
  }

  private class GerritSSHClient extends GerritRestClient {

    private final SshClient sshClient;
    private final GerritAuthData authData;
    private URIish gerritApiUrl;
    private long timeout;

    public GerritSSHClient(
        SshClient sshClient, URIish gerritApiUrl, GerritAuthData authData, long timeout) {
      super(null, null, (HttpClientBuilderExtension) null);
      this.sshClient = sshClient;
      this.gerritApiUrl = gerritApiUrl;
      this.authData = authData;
      this.timeout = timeout;
    }

    @Override
    public HttpResponse requestRest(String path, String requestBody, HttpVerb verb) {
      throw new NotImplementedException(); // Won't make sense for SSH
    }

    @Override
    public HttpResponse request(String path, String requestBody, HttpVerb verb, Header... headers) {
      throw new NotImplementedException(); // Won't make sense for SSH
    }

    @Override
    public JsonElement requestJson(String path, String requestBody, HttpVerb verb)
        throws RestApiException {
      try {
        if (path.startsWith("/")) {
          path = path.substring(1);
        }
        String[] split = path.split("/");
        if (split.length == 0) {
          throw new IllegalArgumentException("Path must not be empty");
        }
        if (split[0].equals("changes")) {
          return getChanges(Arrays.copyOfRange(split, 1, split.length), requestBody, verb);
        } else {
          throw new IllegalArgumentException(split[0] + " not implemented");
        }
      } catch (IOException e) {
        // todo prettify error
        throw new RestApiException("Failed to process request " + path + ", " + requestBody, e);
      }
    }

    private JsonElement getChanges(String[] parts, String requestBody, HttpVerb verb)
        throws IOException {
      if (parts.length == 0) {
        if (verb.equals(HttpVerb.GET)) {
          return request("gerrit query --format=json " + requestBody)
              .orElseThrow(() -> new IOException("Failed to process query"));
        } else {
          throw new IllegalArgumentException(verb + " for changes not implemented");
        }
      } else {
        String changeId = parts[0];
        if (parts.length == 1) {
          throw new IllegalArgumentException("General Changes query not implemented");
        }
        if (parts[1].equals("revisions")) {
          return getRevisions(
              changeId, Arrays.copyOfRange(parts, 2, parts.length), requestBody, verb);
        } else {
          throw new IllegalArgumentException(parts[1] + " not implemented");
        }
      }
    }

    private JsonElement getRevisions(
        String changeId, String[] parts, String requestBody, HttpVerb verb) throws IOException {
      if (parts.length == 0) {
        throw new IllegalArgumentException("General revisions query not implemented");
      }
      String revisionId = parts[0];
      if (parts.length == 1) {
        throw new IllegalArgumentException("Specific revision query not implemented");
      }
      if (parts[1].equals("review")) {
        if (verb.equals(HttpVerb.POST)) {
          String request = String.format("gerrit review %s,%s --json", changeId, revisionId);
          return request(request, requestBody).orElse(JsonNull.INSTANCE);
        } else {
          throw new IllegalArgumentException(verb + " for review not implemented");
        }
      } else if (parts[1].equals("drafts")) {
        if (verb.equals(HttpVerb.PUT)) {
          String request = String.format("gerrit review %s,%s --json", changeId, revisionId);
          Map<String, Map<String, Set>> reviewInput = new HashMap<>();
          Map<String, Set> comments = new HashMap<>();
          DraftInput draftInput = getGson().fromJson(requestBody, DraftInput.class);
          comments.put(draftInput.path, Collections.singleton(draftInput));
          reviewInput.put("comments", comments);
          String json = getGson().toJson(reviewInput);
          return request(request, json).orElseGet(JsonArray::new);
        } else {
          throw new IllegalArgumentException(verb + " for drafts not implemented");
        }
      } else {
        throw new IllegalArgumentException(parts[1] + " not implemented");
      }
    }

    private Optional<JsonElement> request(String request) throws IOException {
      return request(request, null);
    }

    private Optional<JsonElement> request(String request, String stdin) throws IOException {
      sshClient.start();
      ConnectFuture connectFuture =
          sshClient.connect(gerritApiUrl.getUser(), gerritApiUrl.getHost(), gerritApiUrl.getPort());
      connectFuture.await(timeout, TimeUnit.MILLISECONDS);
      if (!connectFuture.isConnected()) {
        throw new IOException(
            String.format(
                "Failed to connect to %s:%s", gerritApiUrl.getHost(), gerritApiUrl.getPort()));
      }
      try (ClientSession session = connectFuture.getSession()) {
        session.auth().await(timeout, TimeUnit.MILLISECONDS);
        if (!session.isAuthenticated()) {
          throw new IOException(
              String.format(
                  "Failed to authenticate to %s:%s",
                  gerritApiUrl.getHost(), gerritApiUrl.getPort()));
        }
        if (stdin != null) {
          try (ChannelExec channel = session.createExecChannel(request)) {
            channel.open().verify().await(timeout, TimeUnit.MILLISECONDS);
            if (!channel.isOpen()) {
              throw new IOException("Failed to open SSH channel");
            }
            IOUtils.write(stdin, channel.getInvertedIn(), StandardCharsets.UTF_8);
            channel.getInvertedIn().flush();
            channel.getInvertedIn().close();
            channel.waitFor(EnumSet.of(ClientChannelEvent.EOF), 0);
            channel.close(false).await(timeout, TimeUnit.MILLISECONDS);
            if (channel.getExitStatus() != null && channel.getExitStatus() != 0) {
              String outputMessage =
                  IOUtils.toString(channel.getInvertedOut(), StandardCharsets.UTF_8);
              String errorMessage =
                  IOUtils.toString(channel.getInvertedErr(), StandardCharsets.UTF_8);
              throw new IOException(
                  String.format(
                      "SSH exited with status '%s', stdout: '%s', stderr: '%s'",
                      channel.getExitStatus(), outputMessage, errorMessage));
            }
            return Optional.ofNullable(
                getGson()
                    .fromJson(
                        IOUtils.toString(channel.getInvertedOut(), StandardCharsets.UTF_8),
                        JsonElement.class));
          }
        } else {
          return Optional.ofNullable(
              getGson().fromJson(session.executeRemoteCommand(request), JsonElement.class));
        }
      }
    }
  }
}
