package ide

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
)

// IDE-F-009 verifies authenticated management operations preserve epoch and workspace fencing.
func TestCodeServerViewManagementAPI(t *testing.T) {
	const viewID = "11111111-1111-4111-8111-111111111111"
	const workspace = "/private/workspaces/view.code-workspace"
	view := ManagedViewInspection{ViewID: viewID, BindingEpoch: "7", WorkspacePath: workspace, State: ManagedViewOpen}
	var requests int
	mux := http.NewServeMux()
	mux.HandleFunc(managementBase, func(writer http.ResponseWriter, request *http.Request) {
		requests++
		if request.Method != http.MethodGet {
			writer.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		_ = json.NewEncoder(writer).Encode(map[string]any{"views": []ManagedViewInspection{view}})
	})
	mux.HandleFunc(managementBase+"/", func(writer http.ResponseWriter, request *http.Request) {
		requests++
		switch request.Method {
		case http.MethodPost:
			var payload struct {
				BindingEpoch  string `json:"bindingEpoch"`
				WorkspacePath string `json:"workspacePath"`
			}
			if err := json.NewDecoder(request.Body).Decode(&payload); err != nil || payload.BindingEpoch != view.BindingEpoch || payload.WorkspacePath != workspace {
				writer.WriteHeader(http.StatusConflict)
				return
			}
			_ = json.NewEncoder(writer).Encode(view)
		case http.MethodGet:
			if request.URL.Query().Get("bindingEpoch") != view.BindingEpoch {
				writer.WriteHeader(http.StatusConflict)
				return
			}
			_ = json.NewEncoder(writer).Encode(view)
		case http.MethodDelete:
			var payload struct {
				BindingEpoch string               `json:"bindingEpoch"`
				Mode         ManagedViewCloseMode `json:"mode"`
			}
			if err := json.NewDecoder(request.Body).Decode(&payload); err != nil || payload.BindingEpoch != view.BindingEpoch || payload.Mode != ManagedViewCloseDiscard {
				writer.WriteHeader(http.StatusConflict)
				return
			}
			result := ManagedViewCloseResult{ManagedViewInspection: ManagedViewInspection{ViewID: viewID, BindingEpoch: view.BindingEpoch, WorkspacePath: workspace, State: ManagedViewClosed}}
			result.Cleanup.Complete = true
			_ = json.NewEncoder(writer).Encode(result)
		default:
			writer.WriteHeader(http.StatusMethodNotAllowed)
		}
	})
	server := httptest.NewServer(mux)
	defer server.Close()
	manager := managementTestManager(t, server)
	ctx := context.Background()
	registered, err := manager.RegisterView(ctx, viewID, 7, workspace)
	if err != nil || registered.ViewID != viewID || registered.BindingEpoch != "7" {
		t.Fatalf("register=%+v err=%v", registered, err)
	}
	inspected, err := manager.InspectView(ctx, viewID, 7)
	if err != nil || inspected.State != ManagedViewOpen {
		t.Fatalf("inspect=%+v err=%v", inspected, err)
	}
	listed, err := manager.ListViews(ctx)
	if err != nil || len(listed) != 1 || listed[0].ViewID != viewID {
		t.Fatalf("list=%+v err=%v", listed, err)
	}
	closed, err := manager.CloseView(ctx, viewID, 7, ManagedViewCloseDiscard)
	if err != nil || closed.State != ManagedViewClosed || !closed.Cleanup.Complete || closed.Mode != ManagedViewCloseDiscard {
		t.Fatalf("close=%+v err=%v", closed, err)
	}
	if requests != 4 {
		t.Fatalf("management requests=%d", requests)
	}
	if _, err := manager.RegisterView(ctx, "invalid", 7, workspace); err == nil {
		t.Fatal("invalid view ID accepted")
	}
	if _, err := manager.RegisterView(ctx, viewID, 0, workspace); err == nil {
		t.Fatal("invalid binding epoch accepted")
	}
}

// IDE-F-009 verifies malformed, oversized, cancelled, and unavailable management responses fail safely.
func TestCodeServerManagementFailurePaths(t *testing.T) {
	const viewID = "11111111-1111-4111-8111-111111111111"
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(writer http.ResponseWriter, request *http.Request) {
		switch request.URL.Query().Get("case") {
		case "server":
			writer.WriteHeader(http.StatusConflict)
			_, _ = io.WriteString(writer, `{"error":"DRAFT_STATE_UNKNOWN"}`)
		case "invalid":
			_, _ = io.WriteString(writer, `{}`)
		case "large":
			_, _ = io.WriteString(writer, strings.Repeat("x", managementBodyLimit+1))
		default:
			if request.Method == http.MethodDelete {
				writer.WriteHeader(http.StatusInternalServerError)
				_, _ = io.WriteString(writer, `{"viewId":"`+viewID+`","bindingEpoch":"7","workspacePath":"/workspace","state":"CLOSED","cleanup":{"complete":false}}`)
				return
			}
			_, _ = io.WriteString(writer, `{"views":null}`)
		}
	})
	server := httptest.NewServer(mux)
	defer server.Close()
	manager := managementTestManager(t, server)
	if _, err := manager.viewRequest(context.Background(), http.MethodGet, "?case=server", nil); FailureCode(err) != "DIRTY" {
		t.Fatalf("native rejection=%v", err)
	}
	if _, err := manager.viewRequest(context.Background(), http.MethodGet, "/?case=invalid", nil); err == nil {
		t.Fatal("invalid managed-view response accepted")
	}
	if _, err := manager.ListViews(context.Background()); err == nil {
		t.Fatal("null managed-view list accepted")
	}
	if _, status, err := manager.managementRequest(context.Background(), http.MethodGet, "?case=large", nil); err == nil || status != 0 {
		t.Fatalf("oversized response status=%d err=%v", status, err)
	}
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, err := manager.managementRequest(cancelled, http.MethodGet, "", nil); FailureCode(err) != "BUSY" {
		t.Fatalf("cancelled request error=%v", err)
	}
	closed, err := manager.CloseView(context.Background(), viewID, 7, ManagedViewCloseDiscard)
	if err != nil || closed.ViewID != viewID || closed.Cleanup.Complete {
		t.Fatalf("uncertain 500 close=%+v err=%v", closed, err)
	}
	server.Close()
	if _, _, err := manager.managementRequest(context.Background(), http.MethodGet, "", nil); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("unavailable request error=%v", err)
	}
}

// IDE-F-009 verifies malformed authenticated management payloads are rejected by every response decoder.
func TestManagementResponsesRejectInvalidShapes(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path == managementBase {
			_, _ = io.WriteString(writer, `{"views":{}}`)
			return
		}
		_, _ = io.WriteString(writer, `{}`)
	}))
	defer server.Close()
	manager := managementTestManager(t, server)
	if _, err := manager.ListViews(context.Background()); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("invalid list=%v", err)
	}
	const viewID = "11111111-1111-4111-8111-111111111111"
	if _, err := manager.InspectView(context.Background(), viewID, 1); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("invalid inspection=%v", err)
	}
	if _, err := manager.RegisterView(context.Background(), viewID, 1, "/workspace"); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("invalid registration=%v", err)
	}
	if _, err := manager.CloseView(context.Background(), viewID, 1, ManagedViewCloseDiscard); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("invalid close=%v", err)
	}
	if _, err := manager.InspectView(context.Background(), "invalid", 1); err == nil {
		t.Fatal("invalid inspection ID accepted")
	}
	if _, err := manager.CloseView(context.Background(), "invalid", 1, ManagedViewCloseDiscard); err == nil {
		t.Fatal("invalid close ID accepted")
	}
}

// IDE-T-039 verifies code-server close results require an explicit cleanup.complete boolean.
func TestCloseViewRequiresCleanupComplete(t *testing.T) {
	const viewID = "11111111-1111-4111-8111-111111111111"
	validView := `"viewId":"` + viewID + `","bindingEpoch":"1","workspacePath":"/workspace","state":"CLOSED"`
	cases := []struct {
		name    string
		body    string
		wantErr bool
	}{
		{name: "missing cleanup", body: `{` + validView + `}`, wantErr: true},
		{name: "missing complete", body: `{` + validView + `,"cleanup":{}}`, wantErr: true},
		{name: "null complete", body: `{` + validView + `,"cleanup":{"complete":null}}`, wantErr: true},
		{name: "non-boolean complete", body: `{` + validView + `,"cleanup":{"complete":"false"}}`, wantErr: true},
		{name: "incomplete cleanup", body: `{` + validView + `,"cleanup":{"complete":false}}`},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
				writer.WriteHeader(http.StatusInternalServerError)
				_, _ = io.WriteString(writer, test.body)
			}))
			defer server.Close()
			manager := managementTestManager(t, server)
			result, err := manager.CloseView(context.Background(), viewID, 1, ManagedViewCloseDiscard)
			if test.wantErr {
				if FailureCode(err) != "UNAVAILABLE" || err.Error() != "managed code-server returned an invalid close result" {
					t.Fatalf("close result=%+v err=%v", result, err)
				}
				return
			}
			if err != nil || result == nil || result.Cleanup.Complete {
				t.Fatalf("incomplete close result=%+v err=%v", result, err)
			}
		})
	}
}

// IDE-F-009 verifies stable native error-code mapping and rejects malformed code fields.
func TestManagedFailureCodes(t *testing.T) {
	cases := []struct {
		status     int
		body, want string
	}{
		{http.StatusConflict, `{"error":"DRAFT_STATE_UNKNOWN"}`, "DIRTY"},
		{http.StatusConflict, `{"error":"VIEW_CLEANUP_INCOMPLETE"}`, "CLEANUP_FAILED"},
		{http.StatusNotFound, `{"error":"VIEW_NOT_FOUND"}`, "VIEW_GONE"},
		{http.StatusConflict, `{"error":"VIEW_BINDING_CONFLICT"}`, "VIEW_IN_USE"},
		{http.StatusConflict, `{"error":"VIEW_EPOCH_ROLLBACK"}`, "VIEW_IN_USE"},
		{http.StatusConflict, `{"error":"VIEW_CLOSED"}`, "VIEW_IN_USE"},
		{http.StatusConflict, `{"error":"NATIVE_CLEANUP_FAILED"}`, "CLEANUP_FAILED"},
		{http.StatusBadRequest, `{"error":"invalid/path"}`, "UNAVAILABLE"},
		{http.StatusBadRequest, `{"error":"lowercase"}`, "UNAVAILABLE"},
		{http.StatusBadRequest, `{"error":"` + strings.Repeat("A", 65) + `"}`, "UNAVAILABLE"},
		{http.StatusInternalServerError, `{}`, "CLEANUP_FAILED"},
	}
	for _, test := range cases {
		if err := managedFailure(test.status, []byte(test.body)); FailureCode(err) != test.want {
			t.Errorf("managedFailure(%d,%s)=%v want %s", test.status, test.body, err, test.want)
		}
	}
}

func managementTestManager(t *testing.T, server *httptest.Server) *codeServerManager {
	t.Helper()
	_, portText, err := net.SplitHostPort(strings.TrimPrefix(server.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	return &codeServerManager{session: &CodeServerSession{Host: "127.0.0.1", Port: port, ManagementToken: "management-secret"}, client: server.Client()}
}
