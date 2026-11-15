//go:build windows

package native

import (
	"fmt"
	"os"
	"path/filepath"
	"unsafe"

	"golang.org/x/sys/windows"
)

func secureNewPrivateDirectory(path string) error { return setWindowsOwnerOnly(path) }

func ensurePrivateDirectory(path string) error {
	if st, err := os.Lstat(path); err == nil {
		if !st.IsDir() || st.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("the private directory path is not a real directory: %s", path)
		}
		attrs, err := windows.GetFileAttributes(windows.StringToUTF16Ptr(path))
		if err != nil {
			return err
		}
		if attrs&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
			return fmt.Errorf("the private path must not be a reparse point: %s", path)
		}
		if err := verifyWindowsOwner(path); err != nil {
			return err
		}
		return setWindowsOwnerOnly(path)
	} else if !os.IsNotExist(err) {
		return err
	}
	parent := filepath.Dir(path)
	if parent != path {
		if _, err := os.Lstat(parent); os.IsNotExist(err) {
			if err := ensurePrivateDirectory(parent); err != nil {
				return err
			}
		} else if err != nil {
			return err
		}
	}
	path16, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	securityDescriptor, err := windowsPrivateSecurityDescriptor()
	if err != nil {
		return err
	}
	security := windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: securityDescriptor}
	if err := windows.CreateDirectory(path16, &security); err != nil {
		if os.IsExist(err) {
			return ensurePrivateDirectory(path)
		}
		return err
	}
	return verifyPrivateDirectory(path)
}

func verifyPrivateDirectory(path string) error {
	st, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !st.IsDir() || st.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("the private directory path is not a real directory: %s", path)
	}
	return verifyWindowsOwnerOnly(path)
}

type windowsPrivateTempFile = privateFileTransactionTemp

type windowsPrivateFileAPI interface {
	OpenTemp(string, string) (windowsPrivateTempFile, error)
	Secure(string) error
	Rename(string, string) error
	Remove(string) error
	Verify(string) error
}

type nativeWindowsPrivateFileAPI struct{}

func (nativeWindowsPrivateFileAPI) OpenTemp(directory, name string) (windowsPrivateTempFile, error) {
	return os.OpenFile(filepath.Join(directory, name), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
}
func (nativeWindowsPrivateFileAPI) Secure(path string) error { return setWindowsOwnerOnly(path) }
func (nativeWindowsPrivateFileAPI) Rename(oldPath, newPath string) error {
	return os.Rename(oldPath, newPath)
}
func (nativeWindowsPrivateFileAPI) Remove(path string) error { return os.Remove(path) }
func (nativeWindowsPrivateFileAPI) Verify(path string) error { return verifyPrivateFile(path) }

func writePrivateFile(path string, data []byte) error {
	if err := verifyPrivateDirectory(filepath.Dir(path)); err != nil {
		return err
	}
	if _, err := os.Lstat(path); err == nil {
		if err := verifyPrivateFile(path); err != nil {
			return err
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	return writePrivateFileTransaction(path, data, nativeWindowsPrivateFileAPI{})
}

func writePrivateFileTransaction(path string, data []byte, api windowsPrivateFileAPI) error {
	directory := filepath.Dir(path)
	tmp, err := api.OpenTemp(directory, "."+filepath.Base(path)+"."+randomSuffix()+".tmp")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	if err := api.Secure(tmpPath); err != nil {
		_ = tmp.Close()
		_ = api.Remove(tmpPath)
		return err
	}
	return publishPrivateFileTransaction(tmp, path, data, api.Rename, api.Remove, api.Verify)
}

func createPrivateFile(path string) error {
	if err := verifyPrivateDirectory(filepath.Dir(path)); err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return setWindowsOwnerOnly(path)
}

func verifyPrivateFile(path string) error {
	st, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !st.Mode().IsRegular() || st.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("the private file path is not a real file: %s", path)
	}
	return verifyWindowsOwnerOnly(path)
}

func windowsPrivateSecurityDescriptor() (*windows.SECURITY_DESCRIPTOR, error) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return nil, err
	}
	sid := user.User.Sid.String()
	return windows.SecurityDescriptorFromString("O:" + sid + "D:P(A;;FA;;;" + sid + ")")
}

func setWindowsOwnerOnly(path string) error {
	sd, err := windowsPrivateSecurityDescriptor()
	if err != nil {
		return err
	}
	owner, _, err := sd.Owner()
	if err != nil {
		return err
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	if err := windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, owner, nil, dacl, nil); err != nil {
		return err
	}
	return verifyWindowsOwnerOnly(path)
}

type windowsPrivatePathAPI interface {
	GetFileAttributes(string) (uint32, error)
	CurrentUserSID() (*windows.SID, error)
	Security(string) (*windows.SID, *windows.ACL, bool, error)
}

type nativeWindowsPrivatePathAPI struct{}

func (nativeWindowsPrivatePathAPI) GetFileAttributes(path string) (uint32, error) {
	return windows.GetFileAttributes(windows.StringToUTF16Ptr(path))
}

func (nativeWindowsPrivatePathAPI) CurrentUserSID() (*windows.SID, error) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return nil, err
	}
	return user.User.Sid, nil
}

func (nativeWindowsPrivatePathAPI) Security(path string) (*windows.SID, *windows.ACL, bool, error) {
	current, err := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return nil, nil, false, err
	}
	owner, _, err := current.Owner()
	if err != nil {
		return nil, nil, false, err
	}
	dacl, _, err := current.DACL()
	if err != nil {
		return nil, nil, false, err
	}
	control, _, err := current.Control()
	return owner, dacl, control&windows.SE_DACL_PROTECTED != 0, err
}

func verifyWindowsOwner(path string) error {
	return verifyWindowsOwnerWithAPI(path, nativeWindowsPrivatePathAPI{})
}

func verifyWindowsOwnerWithAPI(path string, api windowsPrivatePathAPI) error {
	user, err := api.CurrentUserSID()
	if err != nil {
		return err
	}
	owner, _, _, err := api.Security(path)
	if err != nil {
		return err
	}
	if owner == nil || !owner.Equals(user) {
		return fmt.Errorf("the private path is not owned by the current user: %s", path)
	}
	return nil
}

func verifyWindowsOwnerOnly(path string) error {
	return verifyWindowsOwnerOnlyWithAPI(path, nativeWindowsPrivatePathAPI{})
}

func verifyWindowsOwnerOnlyWithAPI(path string, api windowsPrivatePathAPI) error {
	attrs, err := api.GetFileAttributes(path)
	if err != nil {
		return err
	}
	if attrs&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		return fmt.Errorf("the private path must not be a reparse point: %s", path)
	}
	user, err := api.CurrentUserSID()
	if err != nil {
		return err
	}
	owner, dacl, protected, err := api.Security(path)
	if err != nil {
		return err
	}
	if owner == nil || !owner.Equals(user) {
		return fmt.Errorf("the private path is not owned by the current user: %s", path)
	}
	if dacl == nil || dacl.AceCount != 1 {
		return fmt.Errorf("the private path DACL is not owner-only: %s", path)
	}
	if !protected {
		return fmt.Errorf("the private path DACL is not protected: %s", path)
	}
	var ace *windows.ACCESS_ALLOWED_ACE
	if err := windows.GetAce(dacl, 0, &ace); err != nil {
		return err
	}
	aceSID := (*windows.SID)(unsafe.Pointer(&ace.SidStart))
	if ace.Header.AceType != windows.ACCESS_ALLOWED_ACE_TYPE || ace.Header.AceFlags != 0 || ace.Mask != 0x001f01ff || !aceSID.Equals(user) {
		return fmt.Errorf("the private path DACL is not owner-only: %s", path)
	}
	return nil
}
