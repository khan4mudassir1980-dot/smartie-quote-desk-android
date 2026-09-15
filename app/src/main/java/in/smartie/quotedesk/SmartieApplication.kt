package `in`.smartie.quotedesk

import android.app.Application
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.core.AppContainer

class SmartieApplication : Application() {

    /** Built on first use, so Firebase is not touched during onCreate. */
    val container: AppContainer by lazy {
        AppContainer(
            context = this,
            auth = FirebaseAuth.getInstance(),
            firestore = FirebaseFirestore.getInstance(),
        )
    }
}
