package com.locality.mobile

import com.locality.mobile.data.CloudSession

/**
 * "Not read from DataStore yet", as distinct from "signed out".
 *
 * Both the server config and the account are read asynchronously at launch, and
 * a plain null for either would mean "definitely absent" — deciding the first
 * screen on that would flash the welcome screen at someone who is already
 * signed in. Identity comparison (===) against these sentinels is what makes
 * "still loading" a separate state.
 */
internal val UNKNOWN_SESSION = CloudSession(" unknown", "")
