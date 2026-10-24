package com.amardokan.app

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.DecimalFormat

// --- ১. বাংলা ফরম্যাটার ---
object BengaliUtils {
    private val digits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
    fun toBengaliDigits(number: Any): String {
        val str = number.toString()
        val sb = StringBuilder()
        for (ch in str) {
            if (ch in '0'..'9') sb.append(digits[ch - '0']) else sb.append(ch)
        }
        return sb.toString()
    }
    fun parseBengaliNumber(input: String): Double {
        val clean = input.trim()
            .replace('০', '0').replace('১', '1').replace('২', '2').replace('৩', '3').replace('৪', '4')
            .replace('৫', '5').replace('৬', '6').replace('৭', '7').replace('৮', '8').replace('৯', '9')
        return clean.toDoubleOrNull() ?: 0.0
    }
    fun formatCurrency(amount: Double): String {
        val df = DecimalFormat("#,##0.00")
        return "৳ " + toBengaliDigits(df.format(amount))
    }
}

// --- ২. রুম ডেটাবেজ এনটিটি ---
@Serializable
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUnit: String = "পিস",
    val secondaryUnit: String? = null,
    val conversionFactor: Double = 1.0,
    val stockQtyInBaseUnit: Double = 0.0,
    val avgCostPrice: Double = 0.0
)

@Serializable
@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val currentDue: Double = 0.0
)

@Serializable
@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNumber: String,
    val customerId: Long?,
    val timestamp: Long = System.currentTimeMillis(),
    val grandTotal: Double,
    val paidAmount: Double,
    val dueAmount: Double
)

@Serializable
@Entity(tableName = "invoice_items")
data class InvoiceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val productId: Long,
    val productName: String,
    val quantity: Double,
    val unitPrice: Double,
    val unitCostSnapshot: Double,
    val lineTotal: Double
)

@Serializable
@Entity(tableName = "payments")
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String = ""
)

@Serializable
@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val category: String,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis()
)

// --- ৩. ডেটা এক্সেস অবজেক্ট (DAO) ---
@Dao
interface AmarDokanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertProduct(product: ProductEntity): Long
    @Update suspend fun updateProduct(product: ProductEntity)
    @Query("SELECT * FROM products ORDER BY name ASC") fun getAllProducts(): Flow<List<ProductEntity>>
    @Query("SELECT * FROM products WHERE id = :id") suspend fun getProductById(id: Long): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCustomer(customer: CustomerEntity): Long
    @Update suspend fun updateCustomer(customer: CustomerEntity)
    @Query("SELECT * FROM customers ORDER BY name ASC") fun getAllCustomers(): Flow<List<CustomerEntity>>
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun getCustomerById(id: Long): CustomerEntity?

    @Insert suspend fun insertInvoice(invoice: InvoiceEntity): Long
    @Insert suspend fun insertInvoiceItems(items: List<InvoiceItemEntity>)
    @Query("SELECT * FROM invoices ORDER BY timestamp DESC") fun getAllInvoices(): Flow<List<InvoiceEntity>>

    @Insert suspend fun insertPayment(payment: PaymentEntity): Long
    @Query("SELECT * FROM payments ORDER BY timestamp DESC") fun getAllPayments(): Flow<List<PaymentEntity>>

    @Insert suspend fun insertExpense(expense: ExpenseEntity): Long
    @Query("SELECT * FROM expenses ORDER BY timestamp DESC") fun getAllExpenses(): Flow<List<ExpenseEntity>>

    @Transaction
    suspend fun executeCompleteSale(invoice: InvoiceEntity, items: List<InvoiceItemEntity>, paidAmount: Double) {
        val invoiceId = insertInvoice(invoice)
        val finalItems = items.map { item ->
            val product = getProductById(item.productId) ?: throw IllegalStateException("পণ্য নেই!")
            if (product.stockQtyInBaseUnit < item.quantity) throw IllegalStateException("${product.name}-এর স্টক শেষ!")
            updateProduct(product.copy(stockQtyInBaseUnit = product.stockQtyInBaseUnit - item.quantity))
            item.copy(invoiceId = invoiceId, unitCostSnapshot = product.avgCostPrice)
        }
        insertInvoiceItems(finalItems)
        if (invoice.customerId != null && invoice.dueAmount > 0) {
            val customer = getCustomerById(invoice.customerId)
            if (customer != null) updateCustomer(customer.copy(currentDue = customer.currentDue + invoice.dueAmount))
        }
        if (paidAmount > 0) {
            insertPayment(PaymentEntity(customerId = invoice.customerId ?: 0L, amount = paidAmount, note = "বিল জমা"))
        }
    }
}

// --- ৪. ডেটাবেজ বিল্ডার ---
@Database(
    entities = [ProductEntity::class, CustomerEntity::class, InvoiceEntity::class, InvoiceItemEntity::class, PaymentEntity::class, ExpenseEntity::class],
    version = 1, exportSchema = false
)
abstract class AmarDokanDatabase : RoomDatabase() {
    abstract fun dao(): AmarDokanDao
    companion object {
        @Volatile private var INSTANCE: AmarDokanDatabase? = null
        fun getInstance(context: Context): AmarDokanDatabase {
            return INSTANCE ?: synchronized(this) {
                val inst = Room.databaseBuilder(context.applicationContext, AmarDokanDatabase::class.java, "amar_dokan.db")
                    .fallbackToDestructiveMigration().build()
                INSTANCE = inst
                inst
            }
        }
    }
}

// --- ৫. অ্যাপ ও ইউআই ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AmarDokanDatabase.getInstance(applicationContext).dao()

        setContent {
            val navController = rememberNavController()
            val invoices by dao.getAllInvoices().collectAsState(initial = emptyList())
            val payments by dao.getAllPayments().collectAsState(initial = emptyList())
            val expenses by dao.getAllExpenses().collectAsState(initial = emptyList())
            val products by dao.getAllProducts().collectAsState(initial = emptyList())
            val customers by dao.getAllCustomers().collectAsState(initial = emptyList())

            val todaySales = invoices.sumOf { it.grandTotal }
            val totalCustomerDues = customers.sumOf { it.currentDue }
            val cashBalance = payments.sumOf { it.amount } - expenses.sumOf { it.amount }
            val stockValue = products.sumOf { it.stockQtyInBaseUnit * it.avgCostPrice }

            NavHost(navController = navController, startDestination = "dashboard") {
                composable("dashboard") {
                    Scaffold(
                        topBar = {
                            Surface(color = Color(0xFF1B5E20), modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "আমার দোকান",
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    ) { pad ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(pad)
                                .background(Color(0xFFF4F6F8))
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("বর্তমান ক্যাশ ব্যালেন্স", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                                    Text(BengaliUtils.formatCurrency(cashBalance), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("মোট বকেয়া: ${BengaliUtils.formatCurrency(totalCustomerDues)}", color = Color(0xFFFFCDD2))
                                        Text("স্টক ভ্যালু: ${BengaliUtils.formatCurrency(stockValue)}", color = Color(0xFFC8E6C9))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Text("আজকের হিসাব", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("আজকের বিক্রি: ${BengaliUtils.formatCurrency(todaySales)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0288D1))
                                    Text("মোট খরচ: ${BengaliUtils.formatCurrency(expenses.sumOf { it.amount })}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD32F2F))
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))
                            Text("প্রয়োজনীয় কাজ", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(8.dp))

                            val shortcuts = listOf(
                                Pair("নতুন বিক্রি", "new_sale"),
                                Pair("পণ্য ও স্টক যোগ", "add_product"),
                                Pair("কাস্টমার তালিকা", "customers")
                            )

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.height(200.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                userScrollEnabled = false
                            ) {
                                items(shortcuts) { (title, route) ->
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32)),
                                        modifier = Modifier.fillMaxWidth().height(60.dp).clickable { navController.navigate(route) }
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                composable("new_sale") {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    var selectedProduct by remember { mutableStateOf<ProductEntity?>(null) }
                    var qtyStr by remember { mutableStateOf("") }
                    var priceStr by remember { mutableStateOf("") }
                    var paidStr by remember { mutableStateOf("") }

                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("নতুন বিক্রি", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                        Spacer(modifier = Modifier.height(12.dp))

                        Text("পণ্য সিলেক্ট করুন:")
                        LazyColumn(modifier = Modifier.height(150.dp)) {
                            items(products) { prod ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selectedProduct?.id == prod.id) Color(0xFFC8E6C9) else Color.White
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedProduct = prod }
                                ) {
                                    Text("${prod.name} (স্টক: ${BengaliUtils.toBengaliDigits(prod.stockQtyInBaseUnit)} ${prod.baseUnit})", modifier = Modifier.padding(10.dp))
                                }
                            }
                        }

                        OutlinedTextField(
                            value = qtyStr, onValueChange = { qtyStr = it },
                            label = { Text("পরিমাণ (${selectedProduct?.baseUnit ?: "পিস"})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = priceStr, onValueChange = { priceStr = it },
                            label = { Text("দর (প্রতি ইউনিট)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = paidStr, onValueChange = { paidStr = it },
                            label = { Text("নগদ জমা দিল (৳)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val prod = selectedProduct
                                val qty = BengaliUtils.parseBengaliNumber(qtyStr)
                                val price = BengaliUtils.parseBengaliNumber(priceStr)
                                val paid = BengaliUtils.parseBengaliNumber(paidStr)

                                if (prod == null || qty <= 0.0 || price <= 0.0) {
                                    Toast.makeText(context, "সঠিক তথ্য দিন", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val total = qty * price
                                val due = (total - paid).coerceAtLeast(0.0)

                                scope.launch {
                                    try {
                                        val inv = InvoiceEntity(
                                            invoiceNumber = "INV-${System.currentTimeMillis() % 10000}",
                                            customerId = null,
                                            grandTotal = total,
                                            paidAmount = paid,
                                            dueAmount = due
                                        )
                                        val item = InvoiceItemEntity(
                                            invoiceId = 0, productId = prod.id, productName = prod.name,
                                            quantity = qty, unitPrice = price, unitCostSnapshot = prod.avgCostPrice, lineTotal = total
                                        )
                                        dao.executeCompleteSale(inv, listOf(item), paid)
                                        Toast.makeText(context, "বিক্রি সফল!", Toast.LENGTH_SHORT).show()
                                        navController.popBackStack()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text("বিক্রি সংরক্ষণ করুন")
                        }
                    }
                }

                composable("add_product") {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    var name by remember { mutableStateOf("") }
                    var stockStr by remember { mutableStateOf("") }
                    var costStr by remember { mutableStateOf("") }

                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("নতুন পণ্য যোগ করুন", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("পণ্যের নাম") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = stockStr, onValueChange = { stockStr = it }, label = { Text("বর্তমান স্টক (পিস/কেজি)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = costStr, onValueChange = { costStr = it }, label = { Text("কেনা দাম (গড় খরচ)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val s = BengaliUtils.parseBengaliNumber(stockStr)
                                val c = BengaliUtils.parseBengaliNumber(costStr)
                                if (name.isBlank()) return@Button
                                scope.launch {
                                    dao.insertProduct(ProductEntity(name = name, stockQtyInBaseUnit = s, avgCostPrice = c))
                                    Toast.makeText(context, "পণ্য যোগ হয়েছে!", Toast.LENGTH_SHORT).show()
                                    navController.popBackStack()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("সেভ করুন")
                        }
                    }
                }

                composable("customers") {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("কাস্টমার ও বাকির তালিকা", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyColumn {
                            items(customers) { c ->
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(c.name, fontWeight = FontWeight.Bold)
                                        Text("বাকি: ${BengaliUtils.formatCurrency(c.currentDue)}", color = Color.Red)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
