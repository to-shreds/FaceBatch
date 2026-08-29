# Android to web feature parity

The web version preserves FaceBatch's user-facing batch model. Android-only operating-system behavior is implemented with the nearest safe browser equivalent rather than falsely represented as identical.

| FaceBatch capability | Android 0.7.0 | Web 1.0 snapshot |
|---|---|---|
| Select multiple donor images | Yes | Yes |
| Select multiple target images | Yes | Yes |
| Add folders recursively | Android document tree | Browser folder input where supported |
| Full donor-by-target cross product | Yes | Yes |
| AIFaceSwap high-quality profile | Native network client | Private gateway adapter |
| Face Over Auto routing | Native network client | Private gateway adapter |
| FJoy/Magicut profile | Native network client and session rotation | Private gateway adapter and in-memory session rotation |
| TaoAnhDep profile | Native network client | Private gateway adapter |
| Custom multipart endpoint | Yes | Yes, through gateway |
| Custom fields, headers, authentication, JSON result paths, and polling | Yes | Yes |
| Bounded concurrency | Yes | Yes for compatible custom endpoints; built-in providers remain serialized |
| Retries, cancellation, retry failed | Yes | Yes |
| Multi-face target analysis | Yes | Yes, through gateway |
| Sparse face mappings | Yes | Yes |
| Up to 20 shared donors | Yes | Yes |
| Up to 100 target recipe rows | Yes | Yes |
| Duplicate one original into many versions | Yes | Yes |
| Multiple originals configured row by row | Yes | Yes |
| Donor labels below images | Yes | Yes |
| Donor auto-advance | Yes | Yes |
| Completed-row auto-advance | Yes | Yes |
| Edit a completed row without an unwanted jump | Yes | Yes |
| Auto Assign and Same Donor navigation | Yes | Yes |
| Stale-analysis refresh and geometry reconciliation | Yes | Yes |
| Continue after row failure and retry failed rows | Yes | Yes |
| Genuine JPEG output | Yes | Yes |
| Batch output collection | Downloads/FaceBatch via MediaStore | Individual browser downloads or ZIP |
| Remember selections and draft work | Android app storage | IndexedDB and local settings |
| Run while minimized or screen off | Android foreground service | No identical browser capability. State is saved, but the browser may suspend a hidden tab. |
| Protect a private API credential | Android Keystore option | Credential stays in the private gateway; optional gateway token can be stored on the user's browser device |

## Practical meaning

The complete FaceBatch workflow is available in the single HTML frontend. A live built-in-provider batch also needs the included private gateway. Opening the HTML alone is sufficient for interface use, local draft persistence, mock-mode testing, image preparation, and output handling, but it does not turn GitHub Pages into an application server.
