//go:build windows

package native

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/windows"
)

type coverageWindowsPrivatePathAPI struct {
	attrsErr    error
	userErr     error
	securityErr error
	attrs       uint32
	user        *windows.SID
	owner       *windows.SID
	dacl        *windows.ACL
	protected   bool
}

func (api coverageWindowsPrivatePathAPI) GetFileAttributes(string) (uint32, error) {
	return api.attrs, api.attrsErr
}
func (api coverageWindowsPrivatePathAPI) CurrentUserSID() (*windows.SID, error) {
	return api.user, api.userErr
}
func (api coverageWindowsPrivatePathAPI) Security(string) (*windows.SID, *windows.ACL, bool, error) {
	return api.owner, api.dacl, api.protected, api.securityErr
}

func windowsOwnerOnlyACL(t *testing.T, sid *windows.SID) *windows.ACL {
	t.Helper()
	sd, err := windows.SecurityDescriptorFromString("D:P(A;;FA;;;" + sid.String() + ")")
	if err != nil {
		t.Fatal(err)
	}
	acl, _, err := sd.DACL()
	if err != nil {
		t.Fatal(err)
	}
	return acl
}

// TestWindowsPrivatePath_RejectsUnsafeSecurityMetadata covers owner, DACL, reparse, and Win32 query failures.
func TestWindowsPrivatePath_RejectsUnsafeSecurityMetadata(t *testing.T) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	foreign, err := windows.StringToSid("S-1-5-18")
	if err != nil {
		t.Fatal(err)
	}
	base := coverageWindowsPrivatePathAPI{
		attrs:     windows.FILE_ATTRIBUTE_NORMAL,
		user:      user.User.Sid,
		owner:     user.User.Sid,
		dacl:      windowsOwnerOnlyACL(t, user.User.Sid),
		protected: true,
	}
	cases := []struct {
		name string
		api  coverageWindowsPrivatePathAPI
	}{
		{name: "attribute query failure", api: func() coverageWindowsPrivatePathAPI { v := base; v.attrsErr = errors.New("attributes"); return v }()},
		{name: "reparse point", api: func() coverageWindowsPrivatePathAPI {
			v := base
			v.attrs |= windows.FILE_ATTRIBUTE_REPARSE_POINT
			return v
		}()},
		{name: "current user query failure", api: func() coverageWindowsPrivatePathAPI { v := base; v.userErr = errors.New("current user"); return v }()},
		{name: "security query failure", api: func() coverageWindowsPrivatePathAPI { v := base; v.securityErr = errors.New("security"); return v }()},
		{name: "foreign owner", api: func() coverageWindowsPrivatePathAPI { v := base; v.owner = foreign; return v }()},
		{name: "missing DACL", api: func() coverageWindowsPrivatePathAPI { v := base; v.dacl = nil; return v }()},
		{name: "multiple ACEs", api: func() coverageWindowsPrivatePathAPI {
			v := base
			d, err := windows.SecurityDescriptorFromString("D:P(A;;FA;;;" + v.user.String() + ")(A;;FR;;;WD)")
			if err != nil {
				t.Fatal(err)
			}
			v.dacl, _, err = d.DACL()
			if err != nil {
				t.Fatal(err)
			}
			return v
		}()},
		{name: "unprotected DACL", api: func() coverageWindowsPrivatePathAPI { v := base; v.protected = false; return v }()},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			if err := verifyWindowsOwnerOnlyWithAPI("private", test.api); err == nil {
				t.Fatal("unsafe private-path metadata was accepted")
			}
		})
	}
}

// TestWindowsPrivatePath_RejectsUnexpectedACE verifies a single ACE still must grant exactly the owner rights.
func TestWindowsPrivatePath_RejectsUnexpectedACE(t *testing.T) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	acl := windowsOwnerOnlyACL(t, user.User.Sid)
	var ace *windows.ACCESS_ALLOWED_ACE
	if err := windows.GetAce(acl, 0, &ace); err != nil {
		t.Fatal(err)
	}
	ace.Mask = windows.GENERIC_READ
	api := coverageWindowsPrivatePathAPI{attrs: windows.FILE_ATTRIBUTE_NORMAL, user: user.User.Sid, owner: user.User.Sid, dacl: acl, protected: true}
	if err := verifyWindowsOwnerOnlyWithAPI("private", api); err == nil {
		t.Fatal("private path with an unexpected ACE mask was accepted")
	}
}

// TestWindowsPrivateDirectory_RejectsReparseAndNonDirectoryPaths checks existing path types before ACL changes.
func TestWindowsPrivateDirectory_RejectsReparseAndNonDirectoryPaths(t *testing.T) {
	base := t.TempDir()
	file := filepath.Join(base, "regular-file")
	if err := os.WriteFile(file, []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := ensurePrivateDirectory(file); err == nil {
		t.Fatal("regular file accepted as private directory")
	}
	link := filepath.Join(base, "link")
	if err := os.Symlink(file, link); err != nil {
		t.Skipf("cannot create Windows symlink without elevated privileges: %v", err)
	}
	if err := ensurePrivateDirectory(link); err == nil {
		t.Fatal("symbolic link accepted as private directory")
	}
}

type openFailureWindowsPrivateFileAPI struct {
	nativeWindowsPrivateFileAPI
	err error
}

func (api openFailureWindowsPrivateFileAPI) OpenTemp(string, string) (windowsPrivateTempFile, error) {
	return nil, api.err
}

// TestWindowsPrivateFileTransaction_RejectsOpenTempFailure checks an exclusive temporary-file creation failure is returned.
func TestWindowsPrivateFileTransaction_RejectsOpenTempFailure(t *testing.T) {
	apiError := errors.New("injected temporary file creation failure")
	api := openFailureWindowsPrivateFileAPI{err: apiError}
	if err := writePrivateFileTransaction(filepath.Join(t.TempDir(), "target"), []byte("value"), api); !errors.Is(err, apiError) {
		t.Fatalf("temporary file creation error = %v, want %v", err, apiError)
	}
}
